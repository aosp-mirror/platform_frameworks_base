/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;

/**
 * A custom-built layout for the Notification.CallStyle.
 */
@RemoteViews.RemoteView
public class CallLayout extends FrameLayout {
    private final PeopleHelper mPeopleHelper = new PeopleHelper();

    private int mLayoutColor;
    private Icon mLargeIcon;
    private Person mUser;

    private CachingIconView mConversationIconView;
    private CachingIconView mIcon;
    private CachingIconView mConversationIconBadgeBg;
    private TextView mConversationText;
    private boolean mSetDataAsyncEnabled = false;

    public CallLayout(@NonNull Context context) {
        super(context);
    }

    public CallLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CallLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CallLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPeopleHelper.init(getContext());
        mConversationText = findViewById(R.id.conversation_text);
        mConversationIconView = findViewById(R.id.conversation_icon);
        mIcon = findViewById(R.id.icon);
        mConversationIconBadgeBg = findViewById(R.id.conversation_icon_badge_bg);

        // When the small icon is gone, hide the rest of the badge
        mIcon.setOnForceHiddenChangedListener((forceHidden) -> {
            mPeopleHelper.animateViewForceHidden(mConversationIconBadgeBg, forceHidden);
        });
    }

    @NonNull
    private Icon getConversationIcon() {
        CharSequence callerName = "";
        String symbol = "";
        Icon icon = null;
        if (mUser != null) {
            icon = mUser.getIcon();
            callerName = mUser.getName();
            symbol = mPeopleHelper.findNamePrefix(callerName, "");
        }
        if (icon == null) {
            icon = mLargeIcon;
        }
        if (icon == null) {
            icon = mPeopleHelper.createAvatarSymbol(callerName, symbol, mLayoutColor);
        }
        return icon;
    }

    @RemotableViewMethod
    public void setLayoutColor(int color) {
        mLayoutColor = color;
    }

    /**
     * @param color the color of the notification background
     */
    @RemotableViewMethod
    public void setNotificationBackgroundColor(int color) {
        mConversationIconBadgeBg.setImageTintList(ColorStateList.valueOf(color));
    }

    @RemotableViewMethod
    public void setLargeIcon(Icon largeIcon) {
        mLargeIcon = largeIcon;
    }

    /**
     * Set the notification extras so that this layout has access
     */
    @RemotableViewMethod(asyncImpl = "setDataAsync")
    public void setData(Bundle extras) {
        final Person person = getPerson(extras);
        setUser(person);

        final Icon icon = getConversationIcon();
        mConversationIconView.setImageIcon(icon);
    }


    public void setSetDataAsyncEnabled(boolean setDataAsyncEnabled) {
        mSetDataAsyncEnabled = setDataAsyncEnabled;
    }

    /**
     * Async implementation for setData
     */
    public Runnable setDataAsync(Bundle extras) {
        if (!mSetDataAsyncEnabled) {
            return () -> setData(extras);
        }

        final Person person = getPerson(extras);
        setUser(person);

        final Icon conversationIcon = getConversationIcon();
        return mConversationIconView.setImageIconAsync(conversationIcon);
    }

    @Nullable
    private Person getPerson(Bundle extras) {
        return extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
    }

    private void setUser(Person user) {
        mUser = user;
    }

}
