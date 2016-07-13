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

package com.android.documentsui.testing;

import com.android.documentsui.Events.InputEvent;
import com.android.documentsui.TestInputEvent;
import com.android.documentsui.dirlist.UserInputHandler.DocumentDetails;

/**
 * Events and DocDetails are closely related. For the pursposes of this test
 * we coalesce the two in a single, handy-dandy test class.
 */
public class TestEvent extends TestInputEvent implements DocumentDetails {

    private String modelId;
    private boolean inHotspot;

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public int getAdapterPosition() {
        return getItemPosition();
    }

    @Override
    public boolean isInSelectionHotspot(InputEvent event) {
        return inHotspot;
    }

    public TestEvent at(int position) {
        this.position = position;  // this is both "adapter position" and "item position".
        modelId = String.valueOf(position);
        return this;
    }

    public TestEvent shift() {
        this.shiftKeyDow = true;
        return this;
    }

    public TestEvent inHotspot() {
        this.inHotspot = true;
        return this;
    }

    public DocumentDetails getDocument() {
        return this;
    }

    @Override
    public int hashCode() {
        return modelId != null ? modelId.hashCode() : -1;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
          return true;
      }

      if (!(o instanceof TestEvent)) {
          return false;
      }

      TestEvent other = (TestEvent) o;
      return position == other.position
              && modelId == other.modelId
              && shiftKeyDow == other.shiftKeyDow
              && mouseEvent == other.mouseEvent;
    }

    public static final Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private TestEvent mState = new TestEvent();

        public Builder reset() {
            mState = new TestEvent();
            return this;
        }

        public Builder at(int position) {
            mState.position = position;  // this is both "adapter position" and "item position".
            mState.modelId = String.valueOf(position);
            return this;
        }

        public Builder shift() {
            mState.shiftKeyDow = true;
            return this;
        }

        public Builder unshift() {
            mState.shiftKeyDow = false;
            return this;
        }

        public Builder inHotspot() {
            mState.inHotspot = true;
            return this;
        }

        public Builder mouse() {
            mState.mouseEvent = true;
            return this;
        }

        public TestEvent build() {
            // Return a copy, so nobody can mess w/ our internal state.
            TestEvent e = new TestEvent();
            e.position = mState.position;
            e.modelId = mState.modelId;
            e.shiftKeyDow = mState.shiftKeyDow;
            e.mouseEvent = mState.mouseEvent;
            return e;
        }
    }
}
