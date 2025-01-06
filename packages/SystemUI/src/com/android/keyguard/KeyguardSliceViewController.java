/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static android.app.slice.Slice.HINT_LIST_ITEM;

import android.app.PendingIntent;
import android.net.Uri;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.slice.Slice;
import androidx.slice.SliceViewManager;
import androidx.slice.widget.ListContent;
import androidx.slice.widget.RowContent;
import androidx.slice.widget.SliceContent;
import androidx.slice.widget.SliceLiveData;

import com.android.systemui.Dumpable;
import com.android.systemui.Flags;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

/** Controller for a {@link KeyguardSliceView}. */
public class KeyguardSliceViewController extends ViewController<KeyguardSliceView> implements
        Dumpable {
    private static final String TAG = "KeyguardSliceViewCtrl";

    private final Handler mHandler;
    private final Handler mBgHandler;
    private final ActivityStarter mActivityStarter;
    private final ConfigurationController mConfigurationController;
    private final DumpManager mDumpManager;
    private final DisplayTracker mDisplayTracker;
    private int mDisplayId;
    private LiveData<Slice> mLiveData;
    private Uri mKeyguardSliceUri;
    private Slice mSlice;
    private Map<View, PendingIntent> mClickActions;

    ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onDensityOrFontScaleChanged() {
            mView.onDensityOrFontScaleChanged();
        }
        @Override
        public void onThemeChanged() {
            mView.onOverlayChanged();
        }
    };

    Observer<Slice> mObserver = new Observer<Slice>() {
        @Override
        public void onChanged(Slice slice) {
            mSlice = slice;
            showSlice(slice);
        }
    };

    private View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final PendingIntent action = mClickActions.get(v);
            if (action != null && mActivityStarter != null) {
                mActivityStarter.startPendingIntentDismissingKeyguard(action);
            }
        }
    };

    @Inject
    public KeyguardSliceViewController(
            @Main Handler handler,
            @Background Handler bgHandler,
            KeyguardSliceView keyguardSliceView,
            ActivityStarter activityStarter,
            ConfigurationController configurationController,
            DumpManager dumpManager,
            DisplayTracker displayTracker) {
        super(keyguardSliceView);
        mHandler = handler;
        mBgHandler = bgHandler;
        mActivityStarter = activityStarter;
        mConfigurationController = configurationController;
        mDumpManager = dumpManager;
        mDisplayTracker = displayTracker;
    }

    @Override
    protected void onViewAttached() {
        Display display = mView.getDisplay();
        if (display != null) {
            mDisplayId = display.getDisplayId();
        }
        // Make sure we always have the most current slice
        if (mDisplayId == mDisplayTracker.getDefaultDisplayId() && mLiveData != null) {
            mLiveData.observeForever(mObserver);
        }
        mConfigurationController.addCallback(mConfigurationListener);
        mDumpManager.registerNormalDumpable(
                TAG + "@" + Integer.toHexString(
                        KeyguardSliceViewController.this.hashCode()),
                KeyguardSliceViewController.this);
    }

    @Override
    protected void onViewDetached() {
        // TODO(b/117344873) Remove below work around after this issue be fixed.
        if (mDisplayId == mDisplayTracker.getDefaultDisplayId() && mLiveData != null) {
            mLiveData.removeObserver(mObserver);
        }
        mConfigurationController.removeCallback(mConfigurationListener);
        mDumpManager.unregisterDumpable(
                TAG + "@" + Integer.toHexString(
                        KeyguardSliceViewController.this.hashCode()));
    }

    void updateTopMargin(float clockTopTextPadding) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mView.getLayoutParams();
        lp.topMargin = (int) clockTopTextPadding;
        mView.setLayoutParams(lp);
    }

    /**
     * Sets the slice provider Uri.
     */
    public void setupUri(String uriString) {
        if (uriString == null) {
            uriString = KeyguardSliceProvider.KEYGUARD_SLICE_URI;
        }

        boolean wasObserving = false;
        if (mLiveData != null && mLiveData.hasActiveObservers()) {
            wasObserving = true;
            mLiveData.removeObserver(mObserver);
        }

        mKeyguardSliceUri = Uri.parse(uriString);
        mLiveData = SliceLiveData.fromUri(mView.getContext(), mKeyguardSliceUri);

        if (wasObserving) {
            mLiveData.observeForever(mObserver);
        }
    }

    /**
     * Update contents of the view.
     */
    public void refresh() {

        Trace.beginSection("KeyguardSliceViewController#refresh");
        try {
            Slice slice;
            if (KeyguardSliceProvider.KEYGUARD_SLICE_URI.equals(mKeyguardSliceUri.toString())) {
                KeyguardSliceProvider instance = KeyguardSliceProvider.getAttachedInstance();
                if (instance != null) {
                    if (Flags.sliceManagerBinderCallBackground()) {
                        mBgHandler.post(() -> {
                            Slice _slice = instance.onBindSlice(mKeyguardSliceUri);
                            mHandler.post(() -> mObserver.onChanged(_slice));
                        });
                        return;
                    }
                    slice = instance.onBindSlice(mKeyguardSliceUri);
                } else {
                    Log.w(TAG, "Keyguard slice not bound yet?");
                    slice = null;
                }
            } else {
                // TODO: Make SliceViewManager injectable
                slice = SliceViewManager.getInstance(mView.getContext()).bindSlice(
                        mKeyguardSliceUri);
            }
            mObserver.onChanged(slice);
        } finally {
            Trace.endSection();
        }
    }

    void showSlice(Slice slice) {
        Trace.beginSection("KeyguardSliceViewController#showSlice");
        if (slice == null) {
            mView.hideSlice();
            Trace.endSection();
            return;
        }

        ListContent lc = new ListContent(slice);
        RowContent headerContent = lc.getHeader();
        boolean hasHeader =
                headerContent != null && !headerContent.getSliceItem().hasHint(HINT_LIST_ITEM);

        List<SliceContent> subItems = lc.getRowItems().stream().filter(sliceContent -> {
            String itemUri = sliceContent.getSliceItem().getSlice().getUri().toString();
            // Filter out the action row
            return !KeyguardSliceProvider.KEYGUARD_ACTION_URI.equals(itemUri);
        }).collect(Collectors.toList());


        mClickActions = mView.showSlice(hasHeader ? headerContent : null, subItems);

        Trace.endSection();
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("  mSlice: " + mSlice);
        pw.println("  mClickActions: " + mClickActions);
    }
}
