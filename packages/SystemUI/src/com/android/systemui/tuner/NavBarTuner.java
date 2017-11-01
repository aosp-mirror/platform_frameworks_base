/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.KEY;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.KEY_CODE_END;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.KEY_CODE_START;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.KEY_IMAGE_DELIM;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.MENU_IME;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.NAVSPACE;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.NAV_BAR_LEFT;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.NAV_BAR_RIGHT;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.NAV_BAR_VIEWS;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.extractButton;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.extractImage;
import static com.android.systemui.statusbar.phone.NavigationBarInflaterView.extractKeycode;

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.support.v14.preference.PreferenceFragment;
import android.support.v7.preference.DropDownPreference;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.Preference.OnPreferenceChangeListener;
import android.support.v7.preference.Preference.OnPreferenceClickListener;
import android.support.v7.preference.PreferenceCategory;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.widget.EditText;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NavigationBarInflaterView;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;

public class NavBarTuner extends TunerPreferenceFragment {

    private static final String LAYOUT = "layout";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";

    private static final String TYPE = "type";
    private static final String KEYCODE = "keycode";
    private static final String ICON = "icon";

    private static final int[][] ICONS = new int[][]{
            {R.drawable.ic_qs_circle, R.string.tuner_circle},
            {R.drawable.ic_add, R.string.tuner_plus},
            {R.drawable.ic_remove, R.string.tuner_minus},
            {R.drawable.ic_left, R.string.tuner_left},
            {R.drawable.ic_right, R.string.tuner_right},
            {R.drawable.ic_menu, R.string.tuner_menu},
    };

    private final ArrayList<Tunable> mTunables = new ArrayList<>();
    private Handler mHandler;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        mHandler = new Handler();
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getActivity().getActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.nav_bar_tuner);
        bindLayout((ListPreference) findPreference(LAYOUT));
        bindButton(NAV_BAR_LEFT, NAVSPACE, LEFT);
        bindButton(NAV_BAR_RIGHT, MENU_IME, RIGHT);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTunables.forEach(t -> Dependency.get(TunerService.class).removeTunable(t));
    }

    private void addTunable(Tunable tunable, String... keys) {
        mTunables.add(tunable);
        Dependency.get(TunerService.class).addTunable(tunable, keys);
    }

    private void bindLayout(ListPreference preference) {
        addTunable((key, newValue) -> mHandler.post(() -> {
            String val = newValue;
            if (val == null) {
                val = "default";
            }
            preference.setValue(val);
        }), NAV_BAR_VIEWS);
        preference.setOnPreferenceChangeListener((preference1, newValue) -> {
            String val = (String) newValue;
            if ("default".equals(val)) val = null;
            Dependency.get(TunerService.class).setValue(NAV_BAR_VIEWS, val);
            return true;
        });
    }

    private void bindButton(String setting, String def, String k) {
        ListPreference type = (ListPreference) findPreference(TYPE + "_" + k);
        Preference keycode = findPreference(KEYCODE + "_" + k);
        ListPreference icon = (ListPreference) findPreference(ICON + "_" + k);
        setupIcons(icon);
        addTunable((key, newValue) -> mHandler.post(() -> {
            String val = newValue;
            if (val == null) {
                val = def;
            }
            String button = extractButton(val);
            if (button.startsWith(KEY)) {
                type.setValue(KEY);
                String uri = extractImage(button);
                int code = extractKeycode(button);
                icon.setValue(uri);
                updateSummary(icon);
                keycode.setSummary(code + "");
                keycode.setVisible(true);
                icon.setVisible(true);
            } else {
                type.setValue(button);
                keycode.setVisible(false);
                icon.setVisible(false);
            }
        }), setting);
        OnPreferenceChangeListener listener = (preference, newValue) -> {
            mHandler.post(() -> {
                setValue(setting, type, keycode, icon);
                updateSummary(icon);
            });
            return true;
        };
        type.setOnPreferenceChangeListener(listener);
        icon.setOnPreferenceChangeListener(listener);
        keycode.setOnPreferenceClickListener(preference -> {
            EditText editText = new EditText(getContext());
            new AlertDialog.Builder(getContext())
                    .setTitle(preference.getTitle())
                    .setView(editText)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        int code = KeyEvent.KEYCODE_ENTER;
                        try {
                            code = Integer.parseInt(editText.getText().toString());
                        } catch (Exception e) {
                        }
                        keycode.setSummary(code + "");
                        setValue(setting, type, keycode, icon);
                    }).show();
            return true;
        });
    }

    private void updateSummary(ListPreference icon) {
        try {
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14,
                    getContext().getResources().getDisplayMetrics());
            String pkg = icon.getValue().split("/")[0];
            int id = Integer.parseInt(icon.getValue().split("/")[1]);
            SpannableStringBuilder builder = new SpannableStringBuilder();
            Drawable d = Icon.createWithResource(pkg, id)
                    .loadDrawable(getContext());
            d.setTint(Color.BLACK);
            d.setBounds(0, 0, size, size);
            ImageSpan span = new ImageSpan(d, ImageSpan.ALIGN_BASELINE);
            builder.append("  ", span, 0);
            builder.append(" ");
            for (int i = 0; i < ICONS.length; i++) {
                if (ICONS[i][0] == id) {
                    builder.append(getString(ICONS[i][1]));
                }
            }
            icon.setSummary(builder);
        } catch (Exception e) {
            Log.d("NavButton", "Problem with summary", e);
            icon.setSummary(null);
        }
    }

    private void setValue(String setting, ListPreference type, Preference keycode,
            ListPreference icon) {
        String button = type.getValue();
        if (KEY.equals(button)) {
            String uri = icon.getValue();
            int code = KeyEvent.KEYCODE_ENTER;
            try {
                code = Integer.parseInt(keycode.getSummary().toString());
            } catch (Exception e) {
            }
            button = button + KEY_CODE_START + code + KEY_IMAGE_DELIM + uri + KEY_CODE_END;
        }
        Dependency.get(TunerService.class).setValue(setting, button);
    }

    private void setupIcons(ListPreference icon) {
        CharSequence[] labels = new CharSequence[ICONS.length];
        CharSequence[] values = new CharSequence[ICONS.length];
        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14,
                getContext().getResources().getDisplayMetrics());
        for (int i = 0; i < ICONS.length; i++) {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            Drawable d = Icon.createWithResource(getContext().getPackageName(), ICONS[i][0])
                    .loadDrawable(getContext());
            d.setTint(Color.BLACK);
            d.setBounds(0, 0, size, size);
            ImageSpan span = new ImageSpan(d, ImageSpan.ALIGN_BASELINE);
            builder.append("  ", span, 0);
            builder.append(" ");
            builder.append(getString(ICONS[i][1]));
            labels[i] = builder;
            values[i] = getContext().getPackageName() + "/" + ICONS[i][0];
        }
        icon.setEntries(labels);
        icon.setEntryValues(values);
    }
}
