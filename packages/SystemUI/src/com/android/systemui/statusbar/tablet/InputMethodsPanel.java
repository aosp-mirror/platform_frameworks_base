/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import com.android.systemui.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class InputMethodsPanel extends LinearLayout implements StatusBarPanel,
        View.OnClickListener {
    private static final boolean DEBUG = TabletStatusBar.DEBUG;
    private static final String TAG = "InputMethodsPanel";

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onPackageChanged();
        }
    };

    private final InputMethodManager mImm;
    private final IntentFilter mIntentFilter = new IntentFilter();
    private final HashMap<View, Pair<InputMethodInfo, InputMethodSubtype>> mRadioViewAndImiMap =
            new HashMap<View, Pair<InputMethodInfo, InputMethodSubtype>>();
    private final TreeMap<InputMethodInfo, List<InputMethodSubtype>>
            mEnabledInputMethodAndSubtypesCache =
                    new TreeMap<InputMethodInfo, List<InputMethodSubtype>>(
                            new InputMethodComparator());

    private boolean mAttached = false;
    private boolean mPackageChanged = false;
    private Context mContext;
    private IBinder mToken;
    private InputMethodButton mInputMethodSwitchButton;
    private LinearLayout mInputMethodMenuList;
    private boolean mHardKeyboardAvailable;
    private boolean mHardKeyboardEnabled;
    private OnHardKeyboardEnabledChangeListener mHardKeyboardEnabledChangeListener;
    private LinearLayout mHardKeyboardSection;
    private Switch mHardKeyboardSwitch;
    private PackageManager mPackageManager;
    private String mEnabledInputMethodAndSubtypesCacheStr;
    private String mLastSystemLocaleString;
    private View mConfigureImeShortcut;

    private class InputMethodComparator implements Comparator<InputMethodInfo> {
        @Override
        public int compare(InputMethodInfo imi1, InputMethodInfo imi2) {
            if (imi2 == null) return 0;
            if (imi1 == null) return 1;
            if (mPackageManager == null) {
                return imi1.getId().compareTo(imi2.getId());
            }
            CharSequence imiId1 = imi1.loadLabel(mPackageManager) + "/" + imi1.getId();
            CharSequence imiId2 = imi2.loadLabel(mPackageManager) + "/" + imi2.getId();
            return imiId1.toString().compareTo(imiId2.toString());
        }
    }

    public InputMethodsPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public InputMethodsPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mImm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        mIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        mIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        mIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mIntentFilter.addDataScheme("package");
    }

    public void setHardKeyboardEnabledChangeListener(
            OnHardKeyboardEnabledChangeListener listener) {
        mHardKeyboardEnabledChangeListener = listener;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mBroadcastReceiver);
            mAttached = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            getContext().registerReceiver(mBroadcastReceiver, mIntentFilter);
            mAttached = true;
        }
    }

    @Override
    public void onFinishInflate() {
        mInputMethodMenuList = (LinearLayout) findViewById(R.id.input_method_menu_list);
        mHardKeyboardSection = (LinearLayout) findViewById(R.id.hard_keyboard_section);
        mHardKeyboardSwitch = (Switch) findViewById(R.id.hard_keyboard_switch);
        mConfigureImeShortcut = findViewById(R.id.ime_settings_shortcut);
        mConfigureImeShortcut.setOnClickListener(this);
        // TODO: If configurations for IME are not changed, do not update
        // by checking onConfigurationChanged.
        updateUiElements();
    }

    @Override
    public boolean isInContentArea(int x, int y) {
        return false;
    }

    @Override
    public void onClick(View view) {
        if (view == mConfigureImeShortcut) {
            showConfigureInputMethods();
            closePanel(true);
        }
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }

    private void updateHardKeyboardEnabled() {
        if (mHardKeyboardAvailable) {
            final boolean checked = mHardKeyboardSwitch.isChecked();
            if (mHardKeyboardEnabled != checked) {
                mHardKeyboardEnabled = checked;
                if (mHardKeyboardEnabledChangeListener != null)
                    mHardKeyboardEnabledChangeListener.onHardKeyboardEnabledChange(checked);
            }
        }
    }

    public void openPanel() {
        setVisibility(View.VISIBLE);
        updateUiElements();
        if (mInputMethodSwitchButton != null) {
            mInputMethodSwitchButton.setIconImage(R.drawable.ic_sysbar_ime_pressed);
        }
    }

    public void closePanel(boolean closeKeyboard) {
        setVisibility(View.GONE);
        if (mInputMethodSwitchButton != null) {
            mInputMethodSwitchButton.setIconImage(R.drawable.ic_sysbar_ime);
        }
        if (closeKeyboard) {
            mImm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        updateHardKeyboardEnabled();
    }

    private void startActivity(Intent intent) {
        mContext.startActivity(intent);
    }

    private void showConfigureInputMethods() {
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private View createInputMethodItem(
            final InputMethodInfo imi, final InputMethodSubtype subtype) {
        final CharSequence subtypeName;
        if (subtype == null || subtype.overridesImplicitlyEnabledSubtype()) {
            subtypeName = null;
        } else {
            subtypeName = getSubtypeName(imi, subtype);
        }
        final CharSequence imiName = getIMIName(imi);
        final Drawable icon = getSubtypeIcon(imi, subtype);
        final View view = View.inflate(mContext, R.layout.status_bar_input_methods_item, null);
        final ImageView subtypeIcon = (ImageView)view.findViewById(R.id.item_icon);
        final TextView itemTitle = (TextView)view.findViewById(R.id.item_title);
        final TextView itemSubtitle = (TextView)view.findViewById(R.id.item_subtitle);
        final ImageView settingsIcon = (ImageView)view.findViewById(R.id.item_settings_icon);
        final View subtypeView = view.findViewById(R.id.item_subtype);
        if (subtypeName == null) {
            itemTitle.setText(imiName);
            itemSubtitle.setVisibility(View.GONE);
        } else {
            itemTitle.setText(subtypeName);
            itemSubtitle.setVisibility(View.VISIBLE);
            itemSubtitle.setText(imiName);
        }
        subtypeIcon.setImageDrawable(icon);
        subtypeIcon.setContentDescription(itemTitle.getText());
        final String settingsActivity = imi.getSettingsActivity();
        if (!TextUtils.isEmpty(settingsActivity)) {
            settingsIcon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View arg0) {
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setClassName(imi.getPackageName(), settingsActivity);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    closePanel(true);
                }
            });
        } else {
            // Do not show the settings icon if the IME does not have a settings preference
            view.findViewById(R.id.item_vertical_separator).setVisibility(View.GONE);
            settingsIcon.setVisibility(View.GONE);
        }
        mRadioViewAndImiMap.put(
                subtypeView, new Pair<InputMethodInfo, InputMethodSubtype> (imi, subtype));
        subtypeView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Pair<InputMethodInfo, InputMethodSubtype> imiAndSubtype =
                        updateRadioButtonsByView(v);
                closePanel(false);
                setInputMethodAndSubtype(imiAndSubtype.first, imiAndSubtype.second);
            }
        });
        return view;
    }

    private void updateUiElements() {
        updateHardKeyboardSection();

        // TODO: Reuse subtype views.
        mInputMethodMenuList.removeAllViews();
        mRadioViewAndImiMap.clear();
        mPackageManager = mContext.getPackageManager();

        Map<InputMethodInfo, List<InputMethodSubtype>> enabledIMIs =
                getEnabledInputMethodAndSubtypeList();
        Set<InputMethodInfo> cachedImiSet = enabledIMIs.keySet();
        for (InputMethodInfo imi: cachedImiSet) {
            List<InputMethodSubtype> subtypes = enabledIMIs.get(imi);
            if (subtypes == null || subtypes.size() == 0) {
                mInputMethodMenuList.addView(
                        createInputMethodItem(imi, null));
                continue;
            }
            for (InputMethodSubtype subtype: subtypes) {
                mInputMethodMenuList.addView(createInputMethodItem(imi, subtype));
            }
        }
        updateRadioButtons();
    }

    public void setImeToken(IBinder token) {
        mToken = token;
    }

    public void setImeSwitchButton(InputMethodButton imb) {
        mInputMethodSwitchButton = imb;
    }

    private void setInputMethodAndSubtype(InputMethodInfo imi, InputMethodSubtype subtype) {
        if (mToken != null) {
            mImm.setInputMethodAndSubtype(mToken, imi.getId(), subtype);
        } else {
            Log.w(TAG, "IME Token is not set yet.");
        }
    }

    public void setHardKeyboardStatus(boolean available, boolean enabled) {
        if (mHardKeyboardAvailable != available || mHardKeyboardEnabled != enabled) {
            mHardKeyboardAvailable = available;
            mHardKeyboardEnabled = enabled;
            updateHardKeyboardSection();
        }
    }

    private void updateHardKeyboardSection() {
        if (mHardKeyboardAvailable) {
            mHardKeyboardSection.setVisibility(View.VISIBLE);
            if (mHardKeyboardSwitch.isChecked() != mHardKeyboardEnabled) {
                mHardKeyboardSwitch.setChecked(mHardKeyboardEnabled);
            }
        } else {
            mHardKeyboardSection.setVisibility(View.GONE);
        }
    }

    // Turn on the selected radio button when the user chooses the item
    private Pair<InputMethodInfo, InputMethodSubtype> updateRadioButtonsByView(View selectedView) {
        Pair<InputMethodInfo, InputMethodSubtype> selectedImiAndSubtype = null;
        if (mRadioViewAndImiMap.containsKey(selectedView)) {
            for (View radioView: mRadioViewAndImiMap.keySet()) {
                RadioButton subtypeRadioButton =
                        (RadioButton) radioView.findViewById(R.id.item_radio);
                if (subtypeRadioButton == null) {
                    Log.w(TAG, "RadioButton was not found in the selected subtype view");
                    return null;
                }
                if (radioView == selectedView) {
                    Pair<InputMethodInfo, InputMethodSubtype> imiAndSubtype =
                        mRadioViewAndImiMap.get(radioView);
                    selectedImiAndSubtype = imiAndSubtype;
                    subtypeRadioButton.setChecked(true);
                } else {
                    subtypeRadioButton.setChecked(false);
                }
            }
        }
        return selectedImiAndSubtype;
    }

    private void updateRadioButtons() {
        updateRadioButtonsByImiAndSubtype(
                getCurrentInputMethodInfo(), mImm.getCurrentInputMethodSubtype());
    }

    // Turn on the selected radio button at startup
    private void updateRadioButtonsByImiAndSubtype(
            InputMethodInfo imi, InputMethodSubtype subtype) {
        if (imi == null) return;
        if (DEBUG) {
            Log.d(TAG, "Update radio buttons by " + imi.getId() + ", " + subtype);
        }
        for (View radioView: mRadioViewAndImiMap.keySet()) {
            RadioButton subtypeRadioButton =
                    (RadioButton) radioView.findViewById(R.id.item_radio);
            if (subtypeRadioButton == null) {
                Log.w(TAG, "RadioButton was not found in the selected subtype view");
                return;
            }
            Pair<InputMethodInfo, InputMethodSubtype> imiAndSubtype =
                    mRadioViewAndImiMap.get(radioView);
            if (imiAndSubtype.first.getId().equals(imi.getId())
                    && (imiAndSubtype.second == null || imiAndSubtype.second.equals(subtype))) {
                subtypeRadioButton.setChecked(true);
            } else {
                subtypeRadioButton.setChecked(false);
            }
        }
    }

    private TreeMap<InputMethodInfo, List<InputMethodSubtype>>
            getEnabledInputMethodAndSubtypeList() {
        String newEnabledIMIs = Settings.Secure.getString(
                mContext.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        String currentSystemLocaleString =
                mContext.getResources().getConfiguration().locale.toString();
        if (!TextUtils.equals(mEnabledInputMethodAndSubtypesCacheStr, newEnabledIMIs)
                || !TextUtils.equals(mLastSystemLocaleString, currentSystemLocaleString)
                || mPackageChanged) {
            mEnabledInputMethodAndSubtypesCache.clear();
            final List<InputMethodInfo> imis = mImm.getEnabledInputMethodList();
            for (InputMethodInfo imi: imis) {
                mEnabledInputMethodAndSubtypesCache.put(imi,
                        mImm.getEnabledInputMethodSubtypeList(imi, true));
            }
            mEnabledInputMethodAndSubtypesCacheStr = newEnabledIMIs;
            mPackageChanged = false;
            mLastSystemLocaleString = currentSystemLocaleString;
        }
        return mEnabledInputMethodAndSubtypesCache;
    }

    private InputMethodInfo getCurrentInputMethodInfo() {
        String curInputMethodId = Settings.Secure.getString(getContext()
                .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        Set<InputMethodInfo> cachedImiSet = mEnabledInputMethodAndSubtypesCache.keySet();
        // 1. Search IMI in cache
        for (InputMethodInfo imi: cachedImiSet) {
            if (imi.getId().equals(curInputMethodId)) {
                return imi;
            }
        }
        // 2. Get current enabled IMEs and search IMI
        cachedImiSet = getEnabledInputMethodAndSubtypeList().keySet();
        for (InputMethodInfo imi: cachedImiSet) {
            if (imi.getId().equals(curInputMethodId)) {
                return imi;
            }
        }
        return null;
    }

    private CharSequence getIMIName(InputMethodInfo imi) {
        if (imi == null) return null;
        return imi.loadLabel(mPackageManager);
    }

    private CharSequence getSubtypeName(InputMethodInfo imi, InputMethodSubtype subtype) {
        if (imi == null || subtype == null) return null;
        if (DEBUG) {
            Log.d(TAG, "Get text from: " + imi.getPackageName() + subtype.getNameResId()
                    + imi.getServiceInfo().applicationInfo);
        }
        return subtype.getDisplayName(
                mContext, imi.getPackageName(), imi.getServiceInfo().applicationInfo);
    }

    private Drawable getSubtypeIcon(InputMethodInfo imi, InputMethodSubtype subtype) {
        if (imi != null) {
            if (DEBUG) {
                Log.d(TAG, "Update icons of IME: " + imi.getPackageName());
                if (subtype != null) {
                    Log.d(TAG, "subtype =" + subtype.getLocale() + "," + subtype.getMode());
                }
            }
            if (subtype != null) {
                return mPackageManager.getDrawable(imi.getPackageName(), subtype.getIconResId(),
                        imi.getServiceInfo().applicationInfo);
            } else if (imi.getSubtypeCount() > 0) {
                return mPackageManager.getDrawable(imi.getPackageName(),
                        imi.getSubtypeAt(0).getIconResId(),
                        imi.getServiceInfo().applicationInfo);
            } else {
                try {
                    return mPackageManager.getApplicationInfo(
                            imi.getPackageName(), 0).loadIcon(mPackageManager);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "IME can't be found: " + imi.getPackageName());
                }
            }
        }
        return null;
    }

    private void onPackageChanged() {
        if (DEBUG) {
            Log.d(TAG, "onPackageChanged.");
        }
        mPackageChanged = true;
    }

    public interface OnHardKeyboardEnabledChangeListener {
        public void onHardKeyboardEnabledChange(boolean enabled);
    }

}
