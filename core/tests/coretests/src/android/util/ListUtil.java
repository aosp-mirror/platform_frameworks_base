/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import android.app.Instrumentation;
import android.view.KeyEvent;
import android.widget.ListView;

/**
 * Various useful stuff for instrumentation testing listview.
 */
public class ListUtil {

    private final ListView mListView;
    private final Instrumentation mInstrumentation;

    /**
     * @param listView The listview to act on
     * @param instrumentation The instrumentation to use.
     */
    public ListUtil(ListView listView, Instrumentation instrumentation) {
        mListView = listView;
        mInstrumentation = instrumentation;
    }

    /**
     * Set the selected position of the list view.
     * @param pos The desired position.
     */
    public final void setSelectedPosition(final int pos) {
        mListView.post(new Runnable() {
            public void run() {
                mListView.setSelection(pos);
            }
        });
        mInstrumentation.waitForIdleSync();
    }

    /**
     * Get the top of the list.
     */
    public final int getListTop() {
        return mListView.getListPaddingTop();
    }

    /**
     * Get the bottom of the list.
     */
    public final int getListBottom() {
        return mListView.getHeight() - mListView.getListPaddingBottom();
    }

    /**
     * Arrow (up or down as appropriate) to the desired position in the list.
     * @param desiredPos The desired position
     * @throws IllegalStateException if the position can't be reached within 20 presses.
     */
    public final void arrowScrollToSelectedPosition(int desiredPos) {
        if (desiredPos > mListView.getSelectedItemPosition()) {
            arrowDownToSelectedPosition(desiredPos);
        } else {
            arrowUpToSelectedPosition(desiredPos);
        }
    }

    private void arrowDownToSelectedPosition(int position) {
        int maxDowns = 20;
        while(mListView.getSelectedItemPosition() < position && --maxDowns > 0) {
            mInstrumentation.sendCharacterSync(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        if (position != mListView.getSelectedItemPosition()) {
            throw new IllegalStateException("couldn't get to item after 20 downs");
        }

    }

    private void arrowUpToSelectedPosition(int position) {
        int maxUps = 20;
        while(mListView.getSelectedItemPosition() > position && --maxUps > 0) {
            mInstrumentation.sendCharacterSync(KeyEvent.KEYCODE_DPAD_UP);
        }
        if (position != mListView.getSelectedItemPosition()) {
            throw new IllegalStateException("couldn't get to item after 20 ups");
        }
    }

}
