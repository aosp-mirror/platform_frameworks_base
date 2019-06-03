/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.volume;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.media.CarAudioManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.ServiceConnection;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;
import com.android.systemui.plugins.VolumeDialog;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Car version of the volume dialog.
 *
 * Methods ending in "H" must be called on the (ui) handler.
 */
public class CarVolumeDialogImpl implements VolumeDialog {

    private static final String TAG = Util.logTag(CarVolumeDialogImpl.class);

    private static final String XML_TAG_VOLUME_ITEMS = "carVolumeItems";
    private static final String XML_TAG_VOLUME_ITEM = "item";
    private static final int HOVERING_TIMEOUT = 16000;
    private static final int NORMAL_TIMEOUT = 3000;
    private static final int LISTVIEW_ANIMATION_DURATION_IN_MILLIS = 250;
    private static final int DISMISS_DELAY_IN_MILLIS = 50;
    private static final int ARROW_FADE_IN_START_DELAY_IN_MILLIS = 100;

    private final Context mContext;
    private final H mHandler = new H();
    // All the volume items.
    private final SparseArray<VolumeItem> mVolumeItems = new SparseArray<>();
    // Available volume items in car audio manager.
    private final List<VolumeItem> mAvailableVolumeItems = new ArrayList<>();
    // Volume items in the RecyclerView.
    private final List<CarVolumeItem> mCarVolumeLineItems = new ArrayList<>();
    private final KeyguardManager mKeyguard;
    private Window mWindow;
    private CustomDialog mDialog;
    private RecyclerView mListView;
    private CarVolumeItemAdapter mVolumeItemsAdapter;
    private Car mCar;
    private CarAudioManager mCarAudioManager;
    private final CarAudioManager.CarVolumeCallback mVolumeChangeCallback =
            new CarAudioManager.CarVolumeCallback() {
                @Override
                public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
                    // TODO: Include zoneId into consideration.
                    // For instance
                    // - single display + single-zone, ignore zoneId
                    // - multi-display + single-zone, zoneId is fixed, may show volume bar on all
                    // displays
                    // - single-display + multi-zone, may show volume bar on primary display only
                    // - multi-display + multi-zone, may show volume bar on display specified by
                    // zoneId
                    VolumeItem volumeItem = mAvailableVolumeItems.get(groupId);
                    int value = getSeekbarValue(mCarAudioManager, groupId);
                    // find if the group id for which the volume changed is currently being
                    // displayed.
                    boolean isShowing = mCarVolumeLineItems.stream().anyMatch(
                            item -> item.getGroupId() == groupId);
                    // Do not update the progress if it is the same as before. When car audio
                    // manager sets
                    // its group volume caused by the seekbar progress changed, it also triggers
                    // this
                    // callback. Updating the seekbar at the same time could block the continuous
                    // seeking.
                    if (value != volumeItem.progress && isShowing) {
                        volumeItem.carVolumeItem.setProgress(value);
                        volumeItem.progress = value;
                    }
                    if ((flags & AudioManager.FLAG_SHOW_UI) != 0) {
                        mCurrentlyDisplayingGroupId = groupId;
                        mHandler.obtainMessage(H.SHOW,
                                Events.SHOW_REASON_VOLUME_CHANGED).sendToTarget();
                    }
                }

                @Override
                public void onMasterMuteChanged(int zoneId, int flags) {
                    // ignored
                }
            };
    private boolean mHovering;
    private int mCurrentlyDisplayingGroupId;
    private boolean mShowing;
    private boolean mExpanded;
    private View mExpandIcon;
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                mExpanded = false;
                mCarAudioManager = (CarAudioManager) mCar.getCarManager(Car.AUDIO_SERVICE);
                int volumeGroupCount = mCarAudioManager.getVolumeGroupCount();
                // Populates volume slider items from volume groups to UI.
                for (int groupId = 0; groupId < volumeGroupCount; groupId++) {
                    VolumeItem volumeItem = getVolumeItemForUsages(
                            mCarAudioManager.getUsagesForVolumeGroupId(groupId));
                    mAvailableVolumeItems.add(volumeItem);
                    // The first one is the default item.
                    if (groupId == 0) {
                        setuptListItem(0);
                    }
                }

                // If list is already initiated, update its content.
                if (mVolumeItemsAdapter != null) {
                    mVolumeItemsAdapter.notifyDataSetChanged();
                }
                mCarAudioManager.registerCarVolumeCallback(mVolumeChangeCallback);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected!", e);
            }
        }

        /**
         * This does not get called when service is properly disconnected.
         * So we need to also handle cleanups in destroy().
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            cleanupAudioManager();
        }
    };

    private void setuptListItem(int groupId) {
        mCarVolumeLineItems.clear();
        VolumeItem volumeItem = mAvailableVolumeItems.get(groupId);
        volumeItem.defaultItem = true;
        addCarVolumeListItem(volumeItem, /* volumeGroupId = */ groupId,
                R.drawable.car_ic_keyboard_arrow_down, new ExpandIconListener()
        );
    }

    public CarVolumeDialogImpl(Context context) {
        mContext = context;
        mKeyguard = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mCar = Car.createCar(mContext, mServiceConnection);
    }

    private static int getSeekbarValue(CarAudioManager carAudioManager, int volumeGroupId) {
        try {
            return carAudioManager.getGroupVolume(volumeGroupId);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected!", e);
        }
        return 0;
    }

    private static int getMaxSeekbarValue(CarAudioManager carAudioManager, int volumeGroupId) {
        try {
            return carAudioManager.getGroupMaxVolume(volumeGroupId);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected!", e);
        }
        return 0;
    }

    /**
     * Build the volume window and connect to the CarService which registers with car audio
     * manager.
     */
    @Override
    public void init(int windowType, Callback callback) {
        initDialog();

        mCar.connect();
    }

    @Override
    public void destroy() {
        mHandler.removeCallbacksAndMessages(null);

        cleanupAudioManager();
        // unregisterVolumeCallback is not being called when disconnect car, so we manually cleanup
        // audio manager beforehand.
        mCar.disconnect();
    }

    private void initDialog() {
        loadAudioUsageItems();
        mCarVolumeLineItems.clear();
        mDialog = new CustomDialog(mContext);

        mHovering = false;
        mShowing = false;
        mExpanded = false;
        mWindow = mDialog.getWindow();
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        mWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mWindow.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        mWindow.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        final WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle(VolumeDialogImpl.class.getSimpleName());
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.windowAnimations = -1;
        mWindow.setAttributes(lp);

        mDialog.setContentView(R.layout.car_volume_dialog);
        mWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setOnShowListener(dialog -> {
            mListView.setTranslationY(-mListView.getHeight());
            mListView.setAlpha(0);
            mListView.animate()
                    .alpha(1)
                    .translationY(0)
                    .setDuration(LISTVIEW_ANIMATION_DURATION_IN_MILLIS)
                    .setInterpolator(new SystemUIInterpolators.LogDecelerateInterpolator())
                    .start();
        });
        mListView = mWindow.findViewById(R.id.volume_list);
        mListView.setOnHoverListener((v, event) -> {
            int action = event.getActionMasked();
            mHovering = (action == MotionEvent.ACTION_HOVER_ENTER)
                    || (action == MotionEvent.ACTION_HOVER_MOVE);
            rescheduleTimeoutH();
            return true;
        });

        mVolumeItemsAdapter = new CarVolumeItemAdapter(mContext, mCarVolumeLineItems);
        mListView.setAdapter(mVolumeItemsAdapter);
        mListView.setLayoutManager(new LinearLayoutManager(mContext));
    }


    private void showH(int reason) {
        if (D.BUG) {
            Log.d(TAG, "showH r=" + Events.DISMISS_REASONS[reason]);
        }

        mHandler.removeMessages(H.SHOW);
        mHandler.removeMessages(H.DISMISS);
        rescheduleTimeoutH();
        // Refresh the data set before showing.
        mVolumeItemsAdapter.notifyDataSetChanged();
        if (mShowing) {
            return;
        }
        mShowing = true;
        setuptListItem(mCurrentlyDisplayingGroupId);
        mDialog.show();
        Events.writeEvent(mContext, Events.EVENT_SHOW_DIALOG, reason, mKeyguard.isKeyguardLocked());
    }

    private void rescheduleTimeoutH() {
        mHandler.removeMessages(H.DISMISS);
        final int timeout = computeTimeoutH();
        mHandler.sendMessageDelayed(mHandler
                .obtainMessage(H.DISMISS, Events.DISMISS_REASON_TIMEOUT), timeout);

        if (D.BUG) {
            Log.d(TAG, "rescheduleTimeout " + timeout + " " + Debug.getCaller());
        }
    }

    private int computeTimeoutH() {
        return mHovering ? HOVERING_TIMEOUT : NORMAL_TIMEOUT;
    }

    private void dismissH(int reason) {
        if (D.BUG) {
            Log.d(TAG, "dismissH r=" + Events.DISMISS_REASONS[reason]);
        }

        mHandler.removeMessages(H.DISMISS);
        mHandler.removeMessages(H.SHOW);
        if (!mShowing) {
            return;
        }

        mListView.animate().cancel();

        mListView.setTranslationY(0);
        mListView.setAlpha(1);
        mListView.animate()
                .alpha(0)
                .translationY(-mListView.getHeight())
                .setDuration(LISTVIEW_ANIMATION_DURATION_IN_MILLIS)
                .setInterpolator(new SystemUIInterpolators.LogAccelerateInterpolator())
                .withEndAction(() -> mHandler.postDelayed(() -> {
                    if (D.BUG) {
                        Log.d(TAG, "mDialog.dismiss()");
                    }
                    mDialog.dismiss();
                    mShowing = false;
                    mShowing = false;
                    // if mExpandIcon is null that means user never clicked on the expanded arrow
                    // which implies that the dialog is still not expanded. In that case we do
                    // not want to reset the state
                    if (mExpandIcon != null && mExpanded) {
                        toggleDialogExpansion(/* isClicked = */ false);
                    }
                }, DISMISS_DELAY_IN_MILLIS))
                .start();

        Events.writeEvent(mContext, Events.EVENT_DISMISS_DIALOG, reason);
    }

    private void loadAudioUsageItems() {
        try (XmlResourceParser parser = mContext.getResources().getXml(R.xml.car_volume_items)) {
            AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            // Traverse to the first start tag
            while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                    && type != XmlResourceParser.START_TAG) {
                // Do Nothing (moving parser to start element)
            }

            if (!XML_TAG_VOLUME_ITEMS.equals(parser.getName())) {
                throw new RuntimeException("Meta-data does not start with carVolumeItems tag");
            }
            int outerDepth = parser.getDepth();
            int rank = 0;
            while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                    && (type != XmlResourceParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlResourceParser.END_TAG) {
                    continue;
                }
                if (XML_TAG_VOLUME_ITEM.equals(parser.getName())) {
                    TypedArray item = mContext.getResources().obtainAttributes(
                            attrs, R.styleable.carVolumeItems_item);
                    int usage = item.getInt(R.styleable.carVolumeItems_item_usage, -1);
                    if (usage >= 0) {
                        VolumeItem volumeItem = new VolumeItem();
                        volumeItem.rank = rank;
                        volumeItem.icon = item.getResourceId(R.styleable.carVolumeItems_item_icon,
                                0);
                        mVolumeItems.put(usage, volumeItem);
                        rank++;
                    }
                    item.recycle();
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error parsing volume groups configuration", e);
        }
    }

    private VolumeItem getVolumeItemForUsages(int[] usages) {
        int rank = Integer.MAX_VALUE;
        VolumeItem result = null;
        for (int usage : usages) {
            VolumeItem volumeItem = mVolumeItems.get(usage);
            if (volumeItem.rank < rank) {
                rank = volumeItem.rank;
                result = volumeItem;
            }
        }
        return result;
    }

    private CarVolumeItem addCarVolumeListItem(VolumeItem volumeItem, int volumeGroupId,
            int supplementalIconId,
            @Nullable View.OnClickListener supplementalIconOnClickListener) {
        CarVolumeItem carVolumeItem = new CarVolumeItem();
        carVolumeItem.setMax(getMaxSeekbarValue(mCarAudioManager, volumeGroupId));
        int color = mContext.getResources().getColor(R.color.car_volume_dialog_tint);
        int progress = getSeekbarValue(mCarAudioManager, volumeGroupId);
        carVolumeItem.setProgress(progress);
        carVolumeItem.setOnSeekBarChangeListener(
                new CarVolumeDialogImpl.VolumeSeekBarChangeListener(volumeGroupId,
                        mCarAudioManager));
        Drawable primaryIcon = mContext.getResources().getDrawable(volumeItem.icon);
        primaryIcon.mutate().setTint(color);
        carVolumeItem.setPrimaryIcon(primaryIcon);
        if (supplementalIconId != 0) {
            Drawable supplementalIcon = mContext.getResources().getDrawable(supplementalIconId);
            supplementalIcon.mutate().setTint(color);
            carVolumeItem.setSupplementalIcon(supplementalIcon,
                    /* showSupplementalIconDivider= */ true);
            carVolumeItem.setSupplementalIconListener(supplementalIconOnClickListener);
        } else {
            carVolumeItem.setSupplementalIcon(/* drawable= */ null,
                    /* showSupplementalIconDivider= */ false);
        }
        carVolumeItem.setGroupId(volumeGroupId);
        mCarVolumeLineItems.add(carVolumeItem);
        volumeItem.carVolumeItem = carVolumeItem;
        volumeItem.progress = progress;
        return carVolumeItem;
    }

    private VolumeItem findVolumeItem(CarVolumeItem targetItem) {
        for (int i = 0; i < mVolumeItems.size(); ++i) {
            VolumeItem volumeItem = mVolumeItems.valueAt(i);
            if (volumeItem.carVolumeItem == targetItem) {
                return volumeItem;
            }
        }
        return null;
    }

    private void cleanupAudioManager() {
        mCarAudioManager.unregisterCarVolumeCallback(mVolumeChangeCallback);
        mCarVolumeLineItems.clear();
        mCarAudioManager = null;
    }

    /**
     * Wrapper class which contains information of each volume group.
     */
    private static class VolumeItem {

        private int rank;
        private boolean defaultItem = false;
        @DrawableRes
        private int icon;
        private CarVolumeItem carVolumeItem;
        private int progress;
    }

    private final class H extends Handler {

        private static final int SHOW = 1;
        private static final int DISMISS = 2;

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW:
                    showH(msg.arg1);
                    break;
                case DISMISS:
                    dismissH(msg.arg1);
                    break;
                default:
            }
        }
    }

    private final class CustomDialog extends Dialog implements DialogInterface {

        private CustomDialog(Context context) {
            super(context, com.android.systemui.R.style.qs_theme);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            rescheduleTimeoutH();
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected void onStart() {
            super.setCanceledOnTouchOutside(true);
            super.onStart();
        }

        @Override
        protected void onStop() {
            super.onStop();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (isShowing()) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    mHandler.obtainMessage(
                            H.DISMISS, Events.DISMISS_REASON_TOUCH_OUTSIDE).sendToTarget();
                    return true;
                }
            }
            return false;
        }
    }

    private final class ExpandIconListener implements View.OnClickListener {
        @Override
        public void onClick(final View v) {
            mExpandIcon = v;
            toggleDialogExpansion(true);
        }
    }

    private void toggleDialogExpansion(boolean isClicked) {
        mExpanded = !mExpanded;
        Animator inAnimator;
        if (mExpanded) {
            for (int groupId = 0; groupId < mAvailableVolumeItems.size(); ++groupId) {
                if (groupId != mCurrentlyDisplayingGroupId) {
                    VolumeItem volumeItem = mAvailableVolumeItems.get(groupId);
                    addCarVolumeListItem(volumeItem, groupId, 0, null);
                }
            }
            inAnimator = AnimatorInflater.loadAnimator(
                    mContext, R.anim.car_arrow_fade_in_rotate_up);

        } else {
            // Only keeping the default stream if it is not expended.
            Iterator itr = mCarVolumeLineItems.iterator();
            while (itr.hasNext()) {
                CarVolumeItem carVolumeItem = (CarVolumeItem) itr.next();
                if (carVolumeItem.getGroupId() != mCurrentlyDisplayingGroupId) {
                    itr.remove();
                }
            }
            inAnimator = AnimatorInflater.loadAnimator(
                    mContext, R.anim.car_arrow_fade_in_rotate_down);
        }

        Animator outAnimator = AnimatorInflater.loadAnimator(
                mContext, R.anim.car_arrow_fade_out);
        inAnimator.setStartDelay(ARROW_FADE_IN_START_DELAY_IN_MILLIS);
        AnimatorSet animators = new AnimatorSet();
        animators.playTogether(outAnimator, inAnimator);
        if (!isClicked) {
            // Do not animate when the state is called to reset the dialogs view and not clicked
            // by user.
            animators.setDuration(0);
        }
        animators.setTarget(mExpandIcon);
        animators.start();
        mVolumeItemsAdapter.notifyDataSetChanged();
    }

    private final class VolumeSeekBarChangeListener implements OnSeekBarChangeListener {

        private final int mVolumeGroupId;
        private final CarAudioManager mCarAudioManager;

        private VolumeSeekBarChangeListener(int volumeGroupId, CarAudioManager carAudioManager) {
            mVolumeGroupId = volumeGroupId;
            mCarAudioManager = carAudioManager;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser) {
                // For instance, if this event is originated from AudioService,
                // we can ignore it as it has already been handled and doesn't need to be
                // sent back down again.
                return;
            }
            try {
                if (mCarAudioManager == null) {
                    Log.w(TAG, "Ignoring volume change event because the car isn't connected");
                    return;
                }
                mAvailableVolumeItems.get(mVolumeGroupId).progress = progress;
                mAvailableVolumeItems.get(
                        mVolumeGroupId).carVolumeItem.setProgress(progress);
                mCarAudioManager.setGroupVolume(mVolumeGroupId, progress, 0);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected!", e);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
