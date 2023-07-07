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

package android.service.selectiontoolbar;

import static android.view.selectiontoolbar.SelectionToolbarManager.ERROR_DO_NOT_ALLOW_MULTIPLE_TOOL_BAR;
import static android.view.selectiontoolbar.SelectionToolbarManager.NO_TOOLBAR_ID;

import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.selectiontoolbar.ShowInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.UUID;

/**
 * The default implementation of {@link SelectionToolbarRenderService}.
 *
 * <p><b>NOTE:<b/> The requests are handled on the service main thread.
 *
 *  @hide
 */
// TODO(b/214122495): fix class not found then move to system service folder
public final class DefaultSelectionToolbarRenderService extends SelectionToolbarRenderService {

    private static final String TAG = "DefaultSelectionToolbarRenderService";

    // TODO(b/215497659): handle remove if the client process dies.
    // Only show one toolbar, dismiss the old ones and remove from cache
    private final SparseArray<Pair<Long, RemoteSelectionToolbar>> mToolbarCache =
            new SparseArray<>();

    /**
     * Only allow one package to create one toolbar.
     */
    private boolean canShowToolbar(int uid, ShowInfo showInfo) {
        if (showInfo.getWidgetToken() != NO_TOOLBAR_ID) {
            return true;
        }
        return mToolbarCache.indexOfKey(uid) < 0;
    }

    @Override
    public void onShow(int callingUid, ShowInfo showInfo,
            SelectionToolbarRenderService.RemoteCallbackWrapper callbackWrapper) {
        if (!canShowToolbar(callingUid, showInfo)) {
            Slog.e(TAG, "Do not allow multiple toolbar for the app.");
            callbackWrapper.onError(ERROR_DO_NOT_ALLOW_MULTIPLE_TOOL_BAR);
            return;
        }
        long widgetToken = showInfo.getWidgetToken() == NO_TOOLBAR_ID
                ? UUID.randomUUID().getMostSignificantBits()
                : showInfo.getWidgetToken();

        if (mToolbarCache.indexOfKey(callingUid) < 0) {
            RemoteSelectionToolbar toolbar = new RemoteSelectionToolbar(this,
                    widgetToken, showInfo,
                    callbackWrapper, this::transferTouch);
            mToolbarCache.put(callingUid, new Pair<>(widgetToken, toolbar));
        }
        Slog.v(TAG, "onShow() for " + widgetToken);
        Pair<Long, RemoteSelectionToolbar> toolbarPair = mToolbarCache.get(callingUid);
        if (toolbarPair.first == widgetToken) {
            toolbarPair.second.show(showInfo);
        } else {
            Slog.w(TAG, "onShow() for unknown " + widgetToken);
        }
    }

    @Override
    public void onHide(long widgetToken) {
        RemoteSelectionToolbar toolbar = getRemoteSelectionToolbarByTokenLocked(widgetToken);
        if (toolbar != null) {
            Slog.v(TAG, "onHide() for " + widgetToken);
            toolbar.hide(widgetToken);
        }
    }

    @Override
    public void onDismiss(long widgetToken) {
        RemoteSelectionToolbar toolbar = getRemoteSelectionToolbarByTokenLocked(widgetToken);
        if (toolbar != null) {
            Slog.v(TAG, "onDismiss() for " + widgetToken);
            toolbar.dismiss(widgetToken);
            removeRemoteSelectionToolbarByTokenLocked(widgetToken);
        }
    }

    @Override
    public void onToolbarShowTimeout(int callingUid) {
        Slog.w(TAG, "onToolbarShowTimeout for callingUid = " + callingUid);
        Pair<Long, RemoteSelectionToolbar> toolbarPair = mToolbarCache.get(callingUid);
        if (toolbarPair != null) {
            RemoteSelectionToolbar remoteToolbar = toolbarPair.second;
            remoteToolbar.dismiss(toolbarPair.first);
            remoteToolbar.onToolbarShowTimeout();
            mToolbarCache.remove(callingUid);
        }
    }

    private RemoteSelectionToolbar getRemoteSelectionToolbarByTokenLocked(long widgetToken) {
        for (int i = 0; i < mToolbarCache.size(); i++) {
            Pair<Long, RemoteSelectionToolbar> toolbarPair = mToolbarCache.valueAt(i);
            if (toolbarPair.first == widgetToken) {
                return toolbarPair.second;
            }
        }
        return null;
    }

    private void removeRemoteSelectionToolbarByTokenLocked(long widgetToken) {
        for (int i = 0; i < mToolbarCache.size(); i++) {
            Pair<Long, RemoteSelectionToolbar> toolbarPair = mToolbarCache.valueAt(i);
            if (toolbarPair.first == widgetToken) {
                mToolbarCache.remove(mToolbarCache.keyAt(i));
                return;
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        int size = mToolbarCache.size();
        pw.print("number selectionToolbar: "); pw.println(size);
        String pfx = "  ";
        for (int i = 0; i < size; i++) {
            pw.print("#"); pw.println(i);
            int callingUid = mToolbarCache.keyAt(i);
            pw.print(pfx); pw.print("callingUid: "); pw.println(callingUid);
            Pair<Long, RemoteSelectionToolbar> toolbarPair = mToolbarCache.valueAt(i);
            RemoteSelectionToolbar selectionToolbar = toolbarPair.second;
            pw.print(pfx); pw.print("selectionToolbar: ");
            selectionToolbar.dump(pfx, pw);
            pw.println();
        }
    }
}

