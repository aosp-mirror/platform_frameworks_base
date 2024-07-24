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

package com.android.systemui.qs.tiles.dialog;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.Utils;
import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.res.R;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter for showing Wi-Fi networks.
 */
public class InternetAdapter extends RecyclerView.Adapter<InternetAdapter.InternetViewHolder> {

    private static final String TAG = "InternetAdapter";

    private final InternetDialogController mInternetDialogController;
    @Nullable
    private List<WifiEntry> mWifiEntries;
    @VisibleForTesting
    protected int mWifiEntriesCount;
    @VisibleForTesting
    protected int mMaxEntriesCount = InternetDialogController.MAX_WIFI_ENTRY_COUNT;

    protected View mHolderView;
    protected Context mContext;

    public InternetAdapter(InternetDialogController controller) {
        mInternetDialogController = controller;
    }

    @Override
    public InternetViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        mContext = viewGroup.getContext();
        mHolderView = LayoutInflater.from(mContext).inflate(R.layout.internet_list_item,
                viewGroup, false);
        return new InternetViewHolder(mHolderView, mInternetDialogController);
    }

    @Override
    public void onBindViewHolder(@NonNull InternetViewHolder viewHolder, int position) {
        if (mWifiEntries == null || position >= mWifiEntriesCount) {
            return;
        }
        viewHolder.onBind(mWifiEntries.get(position));
    }

    /**
     * Updates the Wi-Fi networks.
     *
     * @param wifiEntries the updated Wi-Fi entries.
     * @param wifiEntriesCount the total number of Wi-Fi entries.
     */
    public void setWifiEntries(@Nullable List<WifiEntry> wifiEntries, int wifiEntriesCount) {
        mWifiEntries = wifiEntries;
        mWifiEntriesCount =
                (wifiEntriesCount < mMaxEntriesCount) ? wifiEntriesCount : mMaxEntriesCount;
    }

    /**
     * Gets the total number of Wi-Fi networks.
     *
     * @return The total number of Wi-Fi entries.
     */
    @Override
    public int getItemCount() {
        return mWifiEntriesCount;
    }

    /**
     * Sets the maximum number of Wi-Fi networks.
     */
    public void setMaxEntriesCount(int count) {
        if (count < 0 || mMaxEntriesCount == count) {
            return;
        }
        mMaxEntriesCount = count;
        if (mWifiEntriesCount > count) {
            mWifiEntriesCount = count;
            notifyDataSetChanged();
        }
    }

    /**
     * ViewHolder for binding Wi-Fi view.
     */
    static class InternetViewHolder extends RecyclerView.ViewHolder {

        final LinearLayout mContainerLayout;
        final LinearLayout mWifiListLayout;
        final LinearLayout mWifiNetworkLayout;
        final ImageView mWifiIcon;
        final TextView mWifiTitleText;
        final TextView mWifiSummaryText;
        final ImageView mWifiEndIcon;
        final Context mContext;
        final InternetDialogController mInternetDialogController;

        InternetViewHolder(View view, InternetDialogController internetDialogController) {
            super(view);
            mContext = view.getContext();
            mInternetDialogController = internetDialogController;
            mContainerLayout = view.requireViewById(R.id.internet_container);
            mWifiListLayout = view.requireViewById(R.id.wifi_list);
            mWifiNetworkLayout = view.requireViewById(R.id.wifi_network_layout);
            mWifiIcon = view.requireViewById(R.id.wifi_icon);
            mWifiTitleText = view.requireViewById(R.id.wifi_title);
            mWifiSummaryText = view.requireViewById(R.id.wifi_summary);
            mWifiEndIcon = view.requireViewById(R.id.wifi_end_icon);
        }

        void onBind(@NonNull WifiEntry wifiEntry) {
            mWifiIcon.setImageDrawable(getWifiDrawable(wifiEntry));
            setWifiNetworkLayout(wifiEntry.getTitle(),
                    Html.fromHtml(wifiEntry.getSummary(false), Html.FROM_HTML_MODE_LEGACY));

            final int connectedState = wifiEntry.getConnectedState();
            final int security = wifiEntry.getSecurity();
            updateEndIcon(connectedState, security);

            mWifiListLayout.setEnabled(shouldEnabled(wifiEntry));
            if (connectedState != WifiEntry.CONNECTED_STATE_DISCONNECTED) {
                mWifiListLayout.setOnClickListener(
                        v -> mInternetDialogController.launchWifiDetailsSetting(
                                wifiEntry.getKey(), v));
                return;
            }
            mWifiListLayout.setOnClickListener(v -> onWifiClick(wifiEntry, v));
        }

        boolean shouldEnabled(@NonNull WifiEntry wifiEntry) {
            if (wifiEntry.canConnect()) {
                return true;
            }
            // If Wi-Fi is connected or saved network, leave it enabled to disconnect or configure.
            if (wifiEntry.canDisconnect() || wifiEntry.isSaved()) {
                return true;
            }
            return false;
        }

        void onWifiClick(@NonNull WifiEntry wifiEntry, @NonNull View view) {
            if (wifiEntry.shouldEditBeforeConnect()) {
                final Intent intent = WifiUtils.getWifiDialogIntent(wifiEntry.getKey(),
                        true /* connectForCaller */);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                mContext.startActivity(intent);
                return;
            }

            if (wifiEntry.canConnect()) {
                mInternetDialogController.connect(wifiEntry);
                return;
            }

            if (wifiEntry.isSaved()) {
                Log.w(TAG, "The saved Wi-Fi network does not allow to connect. SSID:"
                        + wifiEntry.getSsid());
                mInternetDialogController.launchWifiDetailsSetting(wifiEntry.getKey(), view);
            }
        }

        void setWifiNetworkLayout(CharSequence title, CharSequence summary) {
            mWifiTitleText.setText(title);
            if (TextUtils.isEmpty(summary)) {
                mWifiSummaryText.setVisibility(View.GONE);
                return;
            }
            mWifiSummaryText.setVisibility(View.VISIBLE);
            mWifiSummaryText.setText(summary);
        }

        @Nullable
        Drawable getWifiDrawable(@NonNull WifiEntry wifiEntry) {
            Drawable drawable = mInternetDialogController.getWifiDrawable(wifiEntry);
            if (drawable == null) {
                return null;
            }
            drawable.setTint(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorTertiary));
            final AtomicReference<Drawable> shared = new AtomicReference<>();
            shared.set(drawable);
            return shared.get();
        }

        void updateEndIcon(int connectedState, int security) {
            Drawable drawable = null;
            if (connectedState != WifiEntry.CONNECTED_STATE_DISCONNECTED) {
                drawable = mContext.getDrawable(R.drawable.ic_settings_24dp);
            } else if (security != WifiEntry.SECURITY_NONE && security != WifiEntry.SECURITY_OWE) {
                drawable = mContext.getDrawable(R.drawable.ic_friction_lock_closed);
            }
            if (drawable == null) {
                mWifiEndIcon.setVisibility(View.GONE);
                return;
            }
            mWifiEndIcon.setVisibility(View.VISIBLE);
            mWifiEndIcon.setImageDrawable(drawable);
        }
    }
}
