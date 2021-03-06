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

package com.android.systemui.wm;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindowManager;
import android.view.InsetsController;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.WindowInsets;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.TransactionPool;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller that maps between displays and {@link IDisplayWindowInsetsController} in order to
 * give system bar control to SystemUI.
 * {@link R.bool#config_remoteInsetsControllerControlsSystemBars} determines whether this controller
 * takes control or not.
 */
@Singleton
public class DisplaySystemBarsController extends DisplayImeController {

    private static final String TAG = "DisplaySystemBarsController";

    private final Context mContext;
    private final Handler mHandler;

    private SparseArray<PerDisplay> mPerDisplaySparseArray;

    @Inject
    public DisplaySystemBarsController(
            Context context,
            IWindowManager wmService,
            DisplayController displayController,
            @Main Handler mainHandler,
            TransactionPool transactionPool) {
        super(wmService, displayController, mainHandler::post, transactionPool);
        mContext = context;
        mHandler = mainHandler;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        PerDisplay pd = new PerDisplay(displayId);
        pd.register();
        // Lazy loading policy control filters instead of during boot.
        if (mPerDisplaySparseArray == null) {
            mPerDisplaySparseArray = new SparseArray<>();
            BarControlPolicy.reloadFromSetting(mContext);
            BarControlPolicy.registerContentObserver(mContext, mHandler, () -> {
                int size = mPerDisplaySparseArray.size();
                for (int i = 0; i < size; i++) {
                    mPerDisplaySparseArray.valueAt(i).modifyDisplayWindowInsets();
                }
            });
        }
        mPerDisplaySparseArray.put(displayId, pd);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        try {
            mWmService.setDisplayWindowInsetsController(displayId, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to remove insets controller on display " + displayId);
        }
        mPerDisplaySparseArray.remove(displayId);
    }

    @VisibleForTesting
    class PerDisplay extends DisplayImeController.PerDisplay {

        int mDisplayId;
        InsetsController mInsetsController;
        InsetsState mInsetsState = new InsetsState();
        String mPackageName;

        PerDisplay(int displayId) {
            super(displayId, mDisplayController.getDisplayLayout(displayId).rotation());
            mDisplayId = displayId;
            mInsetsController = new InsetsController(
                    new DisplaySystemBarsInsetsControllerHost(mHandler, mInsetsControllerImpl));
        }

        @Override
        public void insetsChanged(InsetsState insetsState) {
            super.insetsChanged(insetsState);
            if (mInsetsState.equals(insetsState)) {
                return;
            }
            mInsetsState.set(insetsState, true /* copySources */);
            mInsetsController.onStateChanged(insetsState);
            if (mPackageName != null) {
                modifyDisplayWindowInsets();
            }
        }

        @Override
        public void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {
            super.insetsControlChanged(insetsState, activeControls);
            mInsetsController.onControlsChanged(activeControls);
        }

        @Override
        public void hideInsets(@WindowInsets.Type.InsetsType int types, boolean fromIme) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                mInsetsController.hide(types);
            } else {
                super.hideInsets(types, fromIme);
            }

        }

        @Override
        public void showInsets(@WindowInsets.Type.InsetsType int types, boolean fromIme) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                mInsetsController.show(types);
            } else {
                super.showInsets(types, fromIme);
            }

        }

        @Override
        public void topFocusedWindowChanged(String packageName) {
            if (Objects.equals(mPackageName, packageName)) {
                return;
            }
            mPackageName = packageName;
            modifyDisplayWindowInsets();
        }

        private void modifyDisplayWindowInsets() {
            if (mPackageName == null) {
                return;
            }
            int[] barVisibilities = BarControlPolicy.getBarVisibilities(mPackageName);
            updateInsetsState(barVisibilities[0], /* visible= */ true);
            updateInsetsState(barVisibilities[1], /* visible= */ false);
            showInsets(barVisibilities[0], /* fromIme= */ false);
            hideInsets(barVisibilities[1], /* fromIme= */ false);
            try {
                mWmService.modifyDisplayWindowInsets(mDisplayId, mInsetsState);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to update window manager service.");
            }
        }

        private void updateInsetsState(@WindowInsets.Type.InsetsType int types, boolean visible) {
            ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
            for (int i = internalTypes.size() - 1; i >= 0; i--) {
                mInsetsState.getSource(internalTypes.valueAt(i)).setVisible(visible);
            }
        }
    }
}
