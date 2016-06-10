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

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.documentsui.NavigationViewManager.Breadcrumb;
import com.android.documentsui.NavigationViewManager.Environment;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;

import java.util.function.Consumer;

/**
 * Dropdown implementation of breadcrumb used for phone device layouts
 */

public final class DropdownBreadcrumb extends Spinner implements Breadcrumb {

    private DropdownAdapter mAdapter;

    public DropdownBreadcrumb(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DropdownBreadcrumb(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DropdownBreadcrumb(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DropdownBreadcrumb(Context context) {
        super(context);
    }

    @Override
    public void setup(Environment env, State state, Consumer<Integer> listener) {
        mAdapter = new DropdownAdapter(state, env);
        setOnItemSelectedListener(
                new OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        listener.accept(position);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
    }

    @Override
    public void show(boolean visibility) {
        if (visibility) {
            setVisibility(VISIBLE);
            setAdapter(mAdapter);
        } else {
            setVisibility(GONE);
            setAdapter(null);
        }
    }

    @Override
    public void postUpdate() {
        setSelection(mAdapter.getCount() - 1, false);
    }

    private static final class DropdownAdapter extends BaseAdapter {
        private Environment mEnv;
        private State mState;

        public DropdownAdapter(State state, Environment env) {
            mState = state;
            mEnv = env;
        }

        @Override
        public int getCount() {
            return mState.stack.size();
        }

        @Override
        public DocumentInfo getItem(int position) {
            return mState.stack.get(mState.stack.size() - position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir_title, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = mEnv.getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = mEnv.getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }

            return convertView;
        }
    }

}
