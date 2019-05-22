/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.car.privacy;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.appops.AppOpItem;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.plugins.ActivityStarter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Layout defining the privacy chip that will be displayed in CarStatusRar with the information for
 * which applications are using AppOpps permission fpr camera, mic and location.
 */
public class OngoingPrivacyChip extends LinearLayout implements View.OnClickListener {

    private Context mContext;

    private LinearLayout mIconsContainer;
    private List<PrivacyItem> mPrivacyItems;
    private static AppOpsController sAppOpsController;
    private UserManager mUserManager;
    private int mCurrentUser;
    private List<Integer> mCurrentUserIds;
    private boolean mListening = false;
    PrivacyDialogBuilder mPrivacyDialogBuilder;
    private LinearLayout mPrivacyChip;
    private ActivityStarter mActivityStarter;

    protected static final int[] OPS = new int[]{
            AppOpsManager.OP_CAMERA,
            AppOpsManager.OP_RECORD_AUDIO,
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION
    };

    public OngoingPrivacyChip(Context context) {
        super(context, null);
        init(context);
    }

    public OngoingPrivacyChip(Context context, AttributeSet attr) {
        super(context, attr);
        init(context);
    }

    public OngoingPrivacyChip(Context context, AttributeSet attr, int defStyle) {
        super(context, attr, defStyle);
        init(context);
    }

    public OngoingPrivacyChip(Context context, AttributeSet attr, int defStyle, int a) {
        super(context, attr, defStyle, a);
        init(context);
    }

    private void init(Context context) {
        mContext = context;
        mPrivacyItems = new ArrayList<>();
        sAppOpsController = Dependency.get(AppOpsController.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mCurrentUser = ActivityManager.getCurrentUser();
        mCurrentUserIds = mUserManager.getProfiles(mCurrentUser).stream().map(
                userInfo -> userInfo.id).collect(Collectors.toList());

        mPrivacyDialogBuilder = new PrivacyDialogBuilder(context, mPrivacyItems);
    }

    private AppOpsController.Callback mCallback = new AppOpsController.Callback() {

        @Override
        public void onActiveStateChanged(int code, int uid, String packageName, boolean active) {
            int userId = UserHandle.getUserId(uid);
            if (mCurrentUserIds.contains(userId)) {
                updatePrivacyList();
            }
        }
    };

    @Override
    public void onFinishInflate() {
        mIconsContainer = findViewById(R.id.icons_container);
        mPrivacyChip = (LinearLayout) findViewById(R.id.car_privacy_chip);
        if (mPrivacyChip != null) {
            mPrivacyChip.setOnClickListener(this);
            setListening(true);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        if (mPrivacyChip != null) {
            setListening(false);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onClick(View v) {
        updatePrivacyList();
        Handler mUiHandler = new Handler(Looper.getMainLooper());
        mUiHandler.post(() -> {
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
        });
    }

    private void setListening(boolean listen) {
        if (mListening == listen) {
            return;
        }
        mListening = listen;
        if (mListening) {
            sAppOpsController.addCallback(OPS, mCallback);
            updatePrivacyList();
        } else {
            sAppOpsController.removeCallback(OPS, mCallback);
        }
    }

    private void updatePrivacyList() {
        mPrivacyItems = mCurrentUserIds.stream()
                .flatMap(item -> sAppOpsController.getActiveAppOpsForUser(item).stream())
                .filter(Objects::nonNull)
                .map(item -> toPrivacyItem(item))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        mPrivacyDialogBuilder = new PrivacyDialogBuilder(mContext, mPrivacyItems);

        Handler refresh = new Handler(Looper.getMainLooper());
        refresh.post(new Runnable() {
            @Override
            public void run() {
                updateView();
            }
        });
    }

    private PrivacyItem toPrivacyItem(AppOpItem appOpItem) {
        PrivacyType type;
        switch (appOpItem.getCode()) {
            case AppOpsManager.OP_CAMERA:
                type = PrivacyType.TYPE_CAMERA;
                break;
            case AppOpsManager.OP_COARSE_LOCATION:
                type = PrivacyType.TYPE_LOCATION;
                break;
            case AppOpsManager.OP_FINE_LOCATION:
                type = PrivacyType.TYPE_LOCATION;
                break;
            case AppOpsManager.OP_RECORD_AUDIO:
                type = PrivacyType.TYPE_MICROPHONE;
                break;
            default:
                return null;
        }
        PrivacyApplication app = new PrivacyApplication(appOpItem.getPackageName(), mContext);
        return new PrivacyItem(type, app, appOpItem.getTimeStarted());
    }

    // Should only be called if the mPrivacyDialogBuilder icons or app changed
    private void updateView() {
        if (mPrivacyItems.isEmpty()) {
            mPrivacyChip.setVisibility(GONE);
            return;
        }
        mPrivacyChip.setVisibility(VISIBLE);
        setIcons(mPrivacyDialogBuilder);

        requestLayout();
    }

    private void setIcons(PrivacyDialogBuilder dialogBuilder) {
        mIconsContainer.removeAllViews();
        dialogBuilder.generateIcons().forEach(item -> {
            int size = mContext.getResources().getDimensionPixelSize(
                    R.dimen.privacy_chip_icon_height);
            ImageView image = new ImageView(mContext);
            image.setImageDrawable(item);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(size, size);

            int leftPadding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.privacy_chip_icon_padding_left);
            int topPadding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.privacy_chip_icon_padding_top);
            int rightPadding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.privacy_chip_icon_padding_right);
            int bottomPadding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.privacy_chip_icon_padding_bottom);
            image.setLayoutParams(layoutParams);
            image.setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
            mIconsContainer.addView(image);
        });
    }
}
