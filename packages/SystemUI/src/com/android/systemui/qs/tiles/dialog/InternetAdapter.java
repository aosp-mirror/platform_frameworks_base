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

import static com.android.wifitrackerlib.WifiEntry.SECURITY_NONE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_OWE;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Adapter for showing Wi-Fi networks.
 */
public class InternetAdapter extends RecyclerView.Adapter<InternetAdapter.InternetViewHolder> {

    private static final String TAG = "InternetAdapter";
    private static final String ACTION_WIFI_DIALOG = "com.android.settings.WIFI_DIALOG";
    private static final String EXTRA_CHOSEN_WIFI_ENTRY_KEY = "key_chosen_wifientry_key";
    private static final String EXTRA_CONNECT_FOR_CALLER = "connect_for_caller";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final InternetDialogController mInternetDialogController;

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
        List<WifiEntry> wifiList = getWifiEntryList();
        if (wifiList != null && wifiList.size() != 0) {
            int count = getItemCount();
            if (wifiList.size() > count) {
                wifiList = getWifiEntryList().subList(0, count - 1);
            }

            if (position < wifiList.size()) {
                viewHolder.onBind(wifiList.get(position));
            }
        } else if (DEBUG) {
            Log.d(TAG, "onBindViewHolder, Wi-Fi entry list = null");
        }
    }

    private List<WifiEntry> getWifiEntryList() {
        if (mInternetDialogController.getWifiEntryList() == null) {
            return null;
        }

        return mInternetDialogController.getWifiEntryList().stream()
                .filter(wifiAp -> wifiAp.getConnectedState()
                        != WifiEntry.CONNECTED_STATE_CONNECTED)
                .limit(getItemCount())
                .collect(Collectors.toList());
    }

    /**
     * The total number of networks (mobile network and entries of Wi-Fi) should be four in
     * {@link InternetDialog}.
     *
     * Airplane mode is ON (mobile network is gone):
     *   Return four Wi-Fi's entries if no connected Wi-Fi.
     *   Return three Wi-Fi's entries if one connected Wi-Fi.
     * Airplane mode is OFF (mobile network is visible):
     *   Return three Wi-Fi's entries if no connected Wi-Fi.
     *   Return two Wi-Fi's entries if one connected Wi-Fi.
     *
     * @return The total number of networks.
     */
    @Override
    public int getItemCount() {
        boolean hasConnectedWifi = mInternetDialogController.getConnectedWifiEntry() != null;
        if (mInternetDialogController.isAirplaneModeEnabled()) {
            return hasConnectedWifi ? 3 : 4;
        } else {
            return hasConnectedWifi ? 2 : 3;
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
        final ImageView mWifiLockedIcon;
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
            mWifiLockedIcon = view.requireViewById(R.id.wifi_locked_icon);
        }

        void onBind(WifiEntry wifiEntry) {
            int security = wifiEntry.getSecurity();
            try {
                mWifiIcon.setImageDrawable(getWifiDrawable(wifiEntry));
                if (isOpenNetwork(security)) {
                    mWifiLockedIcon.setVisibility(View.GONE);
                } else {
                    mWifiLockedIcon.setVisibility(View.VISIBLE);
                    mWifiLockedIcon.setImageDrawable(
                            mContext.getDrawable(R.drawable.ic_friction_lock_closed));
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }

            setWifiNetworkLayout(wifiEntry.getTitle(),
                    Html.fromHtml(wifiEntry.getSummary(false), Html.FROM_HTML_MODE_LEGACY));

            mWifiListLayout.setOnClickListener(v -> {
                if (!isOpenNetwork(security)) {
                    // Popup Wi-Fi password dialog condition:
                    // 1. The access point is a non-open network.
                    // 2. The Wi-Fi connection is not connected with this access point.
                    final Intent intent = new Intent(ACTION_WIFI_DIALOG);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    intent.putExtra(EXTRA_CHOSEN_WIFI_ENTRY_KEY, wifiEntry.getKey());
                    intent.putExtra(EXTRA_CONNECT_FOR_CALLER, false);
                    mContext.startActivity(intent);
                }
                mInternetDialogController.connect(wifiEntry);
            });
        }

        /** Return true if this is an open network AccessPoint. */
        boolean isOpenNetwork(int security) {
            return security == SECURITY_NONE
                    || security == SECURITY_OWE;
        }

        void setWifiNetworkLayout(CharSequence title, CharSequence summary) {
            mWifiNetworkLayout.setVisibility(View.VISIBLE);
            mWifiTitleText.setText(title);
            if (TextUtils.isEmpty(summary)) {
                mWifiTitleText.setGravity(Gravity.CENTER);
                mWifiSummaryText.setVisibility(View.GONE);
                return;
            } else {
                mWifiTitleText.setGravity(Gravity.BOTTOM);
                mWifiSummaryText.setGravity(Gravity.TOP);
                mWifiSummaryText.setVisibility(View.VISIBLE);
            }
            mWifiSummaryText.setText(summary);
        }

        Drawable getWifiDrawable(WifiEntry wifiEntry) throws Throwable {
            Drawable drawable = mContext.getDrawable(
                    com.android.internal.R.drawable.ic_wifi_signal_0);

            AtomicReference<Drawable> shared = new AtomicReference<>();
            final @ColorInt int tint = Utils.getColorAttrDefaultColor(mContext,
                    android.R.attr.colorControlNormal);
            Drawable signalDrawable = mContext.getDrawable(
                    Utils.getWifiIconResource(wifiEntry.getLevel()));
            signalDrawable.setTint(tint);
            shared.set(signalDrawable);
            drawable = shared.get();
            return drawable;
        }
    }
}
