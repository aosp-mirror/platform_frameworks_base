/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.view.Menu;

public interface MenuManager {

  /** @See DirectoryFragment.SelectionModeListener#updateActionMenu */
  void updateActionMenu(Menu mMenu, MenuManager.SelectionDetails selectionDetails);
  /** @See Activity#onPrepareOptionsMenu */
  void updateOptionMenu(Menu menu, DirectoryDetails details);

  /**
   * Access to meta data about the selection.
   */
  interface SelectionDetails {
    boolean containsDirectories();
    boolean containsPartialFiles();

    // TODO: Update these to express characteristics instead of answering concrete questions,
    // since the answer to those questions is (or can be) activity specific.
    boolean canDelete();
    boolean canRename();
  }

  public static class DirectoryDetails {
      private final BaseActivity mActivity;

      public DirectoryDetails(BaseActivity activity) {
        mActivity = activity;
      }

      public boolean shouldShowFancyFeatures() {
        return Shared.shouldShowFancyFeatures(mActivity);
      }

      public boolean hasRootSettings() {
        return mActivity.getCurrentRoot().hasSettings();
      }

      public boolean hasItemsToPaste() {
          return false;
      }

      public boolean isInRecents() {
        return mActivity.getCurrentDirectory() == null;
      }

      public boolean canCreateDirectory() {
        return mActivity.canCreateDirectory();
      }
  }
}