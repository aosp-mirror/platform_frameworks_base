/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnDragListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTile.Host.Callback;
import com.android.systemui.qs.QSTile.ResourceIcon;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.tiles.IntentTile;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.policy.SecurityController;

import java.util.ArrayList;
import java.util.List;

public class QsTuner extends Fragment implements Callback {

    private static final String TAG = "QsTuner";

    private static final int MENU_RESET = Menu.FIRST;

    private DraggableQsPanel mQsPanel;
    private CustomHost mTileHost;

    private FrameLayout mDropTarget;

    private ScrollView mScrollRoot;

    private FrameLayout mAddTarget;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(0, MENU_RESET, 0, com.android.internal.R.string.reset);
    }

    public void onResume() {
        super.onResume();
        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER_QS, true);
    }

    public void onPause() {
        super.onPause();
        MetricsLogger.visibility(getContext(), MetricsLogger.TUNER_QS, false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_RESET:
                mTileHost.reset();
                break;
            case android.R.id.home:
                getFragmentManager().popBackStack();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        mScrollRoot = (ScrollView) inflater.inflate(R.layout.tuner_qs, container, false);

        mQsPanel = new DraggableQsPanel(getContext());
        mTileHost = new CustomHost(getContext());
        mTileHost.setCallback(this);
        mQsPanel.setTiles(mTileHost.getTiles());
        mQsPanel.setHost(mTileHost);
        mQsPanel.refreshAllTiles();
        ((ViewGroup) mScrollRoot.findViewById(R.id.all_details)).addView(mQsPanel, 0);

        mDropTarget = (FrameLayout) mScrollRoot.findViewById(R.id.remove_target);
        setupDropTarget();
        mAddTarget = (FrameLayout) mScrollRoot.findViewById(R.id.add_target);
        setupAddTarget();
        return mScrollRoot;
    }

    @Override
    public void onDestroyView() {
        mTileHost.destroy();
        super.onDestroyView();
    }

    private void setupDropTarget() {
        QSTileView tileView = new QSTileView(getContext());
        QSTile.State state = new QSTile.State();
        state.visible = true;
        state.icon = ResourceIcon.get(R.drawable.ic_delete);
        state.label = getString(com.android.internal.R.string.delete);
        tileView.onStateChanged(state);
        mDropTarget.addView(tileView);
        mDropTarget.setVisibility(View.GONE);
        new DragHelper(tileView, new DropListener() {
            @Override
            public void onDrop(String sourceText) {
                mTileHost.remove(sourceText);
            }
        });
    }

    private void setupAddTarget() {
        QSTileView tileView = new QSTileView(getContext());
        QSTile.State state = new QSTile.State();
        state.visible = true;
        state.icon = ResourceIcon.get(R.drawable.ic_add_circle_qs);
        state.label = getString(R.string.add_tile);
        tileView.onStateChanged(state);
        mAddTarget.addView(tileView);
        tileView.setClickable(true);
        tileView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mTileHost.showAddDialog();
            }
        });
    }

    public void onStartDrag() {
        mDropTarget.post(new Runnable() {
            @Override
            public void run() {
                mDropTarget.setVisibility(View.VISIBLE);
                mAddTarget.setVisibility(View.GONE);
            }
        });
    }

    public void stopDrag() {
        mDropTarget.post(new Runnable() {
            @Override
            public void run() {
                mDropTarget.setVisibility(View.GONE);
                mAddTarget.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onTilesChanged() {
        mQsPanel.setTiles(mTileHost.getTiles());
    }

    private static int getLabelResource(String spec) {
        if (spec.equals("wifi")) return R.string.quick_settings_wifi_label;
        else if (spec.equals("bt")) return R.string.quick_settings_bluetooth_label;
        else if (spec.equals("inversion")) return R.string.quick_settings_inversion_label;
        else if (spec.equals("cell")) return R.string.quick_settings_cellular_detail_title;
        else if (spec.equals("airplane")) return R.string.airplane_mode;
        else if (spec.equals("dnd")) return R.string.quick_settings_dnd_label;
        else if (spec.equals("rotation")) return R.string.quick_settings_rotation_locked_label;
        else if (spec.equals("flashlight")) return R.string.quick_settings_flashlight_label;
        else if (spec.equals("location")) return R.string.quick_settings_location_label;
        else if (spec.equals("cast")) return R.string.quick_settings_cast_title;
        else if (spec.equals("hotspot")) return R.string.quick_settings_hotspot_label;
        return 0;
    }

    private static class CustomHost extends QSTileHost {

        public CustomHost(Context context) {
            super(context, null, null, null, null, null, null, null, null, null,
                    null, null, new BlankSecurityController());
        }

        @Override
        protected QSTile<?> createTile(String tileSpec) {
            return new DraggableTile(this, tileSpec);
        }

        public void replace(String oldTile, String newTile) {
            if (oldTile.equals(newTile)) {
                return;
            }
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_REORDER, oldTile + ","
                    + newTile);
            List<String> order = new ArrayList<>(mTileSpecs);
            int index = order.indexOf(oldTile);
            if (index < 0) {
                Log.e(TAG, "Can't find " + oldTile);
                return;
            }
            order.remove(newTile);
            order.add(index, newTile);
            setTiles(order);
        }

        public void remove(String tile) {
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_REMOVE, tile);
            List<String> tiles = new ArrayList<>(mTileSpecs);
            tiles.remove(tile);
            setTiles(tiles);
        }

        public void add(String tile) {
            MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_ADD, tile);
            List<String> tiles = new ArrayList<>(mTileSpecs);
            tiles.add(tile);
            setTiles(tiles);
        }

        public void reset() {
            Secure.putStringForUser(getContext().getContentResolver(),
                    TILES_SETTING, "default", ActivityManager.getCurrentUser());
        }

        private void setTiles(List<String> tiles) {
            Secure.putStringForUser(getContext().getContentResolver(), TILES_SETTING,
                    TextUtils.join(",", tiles), ActivityManager.getCurrentUser());
        }

        public void showAddDialog() {
            List<String> tiles = mTileSpecs;
            int numBroadcast = 0;
            for (int i = 0; i < tiles.size(); i++) {
                if (tiles.get(i).startsWith(IntentTile.PREFIX)) {
                    numBroadcast++;
                }
            }
            String[] defaults =
                getContext().getString(R.string.quick_settings_tiles_default).split(",");
            final String[] available = new String[defaults.length + 1
                                                  - (tiles.size() - numBroadcast)];
            final String[] availableTiles = new String[available.length];
            int index = 0;
            for (int i = 0; i < defaults.length; i++) {
                if (tiles.contains(defaults[i])) {
                    continue;
                }
                int resource = getLabelResource(defaults[i]);
                if (resource != 0) {
                    availableTiles[index] = defaults[i];
                    available[index++] = getContext().getString(resource);
                } else {
                    availableTiles[index] = defaults[i];
                    available[index++] = defaults[i];
                }
            }
            available[index++] = getContext().getString(R.string.broadcast_tile);
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.add_tile)
                    .setItems(available, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (which < available.length - 1) {
                                add(availableTiles[which]);
                            } else {
                                showBroadcastTileDialog();
                            }
                        }
                    }).show();
        }

        public void showBroadcastTileDialog() {
            final EditText editText = new EditText(getContext());
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.broadcast_tile)
                    .setView(editText)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            String action = editText.getText().toString();
                            if (isValid(action)) {
                                add(IntentTile.PREFIX + action + ')');
                            }
                        }
                    }).show();
        }

        private boolean isValid(String action) {
            for (int i = 0; i < action.length(); i++) {
                char c = action.charAt(i);
                if (!Character.isAlphabetic(c) && !Character.isDigit(c) && c != '.') {
                    return false;
                }
            }
            return true;
        }

        private static class BlankSecurityController implements SecurityController {
            @Override
            public boolean hasDeviceOwner() {
                return false;
            }

            @Override
            public boolean hasProfileOwner() {
                return false;
            }

            @Override
            public String getDeviceOwnerName() {
                return null;
            }

            @Override
            public String getProfileOwnerName() {
                return null;
            }

            @Override
            public boolean isVpnEnabled() {
                return false;
            }

            @Override
            public String getPrimaryVpnName() {
                return null;
            }

            @Override
            public String getProfileVpnName() {
                return null;
            }

            @Override
            public void onUserSwitched(int newUserId) {
            }

            @Override
            public void addCallback(SecurityControllerCallback callback) {
            }

            @Override
            public void removeCallback(SecurityControllerCallback callback) {
            }
        }
    }

    private static class DraggableTile extends QSTile<QSTile.State>
            implements DropListener {
        private String mSpec;
        private QSTileView mView;

        protected DraggableTile(QSTile.Host host, String tileSpec) {
            super(host);
            Log.d(TAG, "Creating tile " + tileSpec);
            mSpec = tileSpec;
        }

        @Override
        public QSTileView createTileView(Context context) {
            mView = super.createTileView(context);
            return mView;
        }

        @Override
        public boolean supportsDualTargets() {
            return "wifi".equals(mSpec) || "bt".equals(mSpec);
        }

        @Override
        public void setListening(boolean listening) {
        }

        @Override
        protected QSTile.State newTileState() {
            return new QSTile.State();
        }

        @Override
        protected void handleClick() {
        }

        @Override
        protected void handleUpdateState(QSTile.State state, Object arg) {
            state.visible = true;
            state.icon = ResourceIcon.get(getIcon());
            state.label = getLabel();
        }

        private String getLabel() {
            int resource = getLabelResource(mSpec);
            if (resource != 0) {
                return mContext.getString(resource);
            }
            if (mSpec.startsWith(IntentTile.PREFIX)) {
                int lastDot = mSpec.lastIndexOf('.');
                if (lastDot >= 0) {
                    return mSpec.substring(lastDot + 1, mSpec.length() - 1);
                } else {
                    return mSpec.substring(IntentTile.PREFIX.length(), mSpec.length() - 1);
                }
            }
            return mSpec;
        }

        private int getIcon() {
            if (mSpec.equals("wifi")) return R.drawable.ic_qs_wifi_full_3;
            else if (mSpec.equals("bt")) return R.drawable.ic_qs_bluetooth_connected;
            else if (mSpec.equals("inversion")) return R.drawable.ic_invert_colors_enable;
            else if (mSpec.equals("cell")) return R.drawable.ic_qs_signal_full_3;
            else if (mSpec.equals("airplane")) return R.drawable.ic_signal_airplane_enable;
            else if (mSpec.equals("dnd")) return R.drawable.ic_qs_dnd_on;
            else if (mSpec.equals("rotation")) return R.drawable.ic_portrait_from_auto_rotate;
            else if (mSpec.equals("flashlight")) return R.drawable.ic_signal_flashlight_enable;
            else if (mSpec.equals("location")) return R.drawable.ic_signal_location_enable;
            else if (mSpec.equals("cast")) return R.drawable.ic_qs_cast_on;
            else if (mSpec.equals("hotspot")) return R.drawable.ic_hotspot_enable;
            return R.drawable.android;
        }

        @Override
        public int getMetricsCategory() {
            return 20000;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof DraggableTile) {
                return mSpec.equals(((DraggableTile) o).mSpec);
            }
            return false;
        }

        @Override
        public void onDrop(String sourceText) {
            ((CustomHost) mHost).replace(mSpec, sourceText);
        }

    }

    private class DragHelper implements OnDragListener {

        private final View mView;
        private final DropListener mListener;

        public DragHelper(View view, DropListener dropListener) {
            mView = view;
            mListener = dropListener;
            mView.setOnDragListener(this);
        }

        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    mView.setBackgroundColor(0x77ffffff);
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    stopDrag();
                case DragEvent.ACTION_DRAG_EXITED:
                    mView.setBackgroundColor(0x0);
                    break;
                case DragEvent.ACTION_DROP:
                    stopDrag();
                    String text = event.getClipData().getItemAt(0).getText().toString();
                    mListener.onDrop(text);
                    break;
            }
            return true;
        }

    }

    public interface DropListener {
        void onDrop(String sourceText);
    }

    private class DraggableQsPanel extends QSPanel implements OnTouchListener {
        public DraggableQsPanel(Context context) {
            super(context);
            mBrightnessView.setVisibility(View.GONE);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            for (TileRecord r : mRecords) {
                new DragHelper(r.tileView, (DraggableTile) r.tile);
                r.tileView.setTag(r.tile);
                r.tileView.setOnTouchListener(this);

                for (int i = 0; i < r.tileView.getChildCount(); i++) {
                    r.tileView.getChildAt(i).setClickable(false);
                }
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    String tileSpec = (String) ((DraggableTile) v.getTag()).mSpec;
                    ClipData data = ClipData.newPlainText(tileSpec, tileSpec);
                    v.startDrag(data, new View.DragShadowBuilder(v), null, 0);
                    onStartDrag();
                    return true;
            }
            return false;
        }
    }

}
