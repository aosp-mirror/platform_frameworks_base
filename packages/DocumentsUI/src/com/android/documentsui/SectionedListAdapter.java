/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;

import java.util.ArrayList;

/**
 * Adapter that combines multiple adapters as sections, asking each section to
 * provide a header, and correctly handling item types across child adapters.
 */
public class SectionedListAdapter extends BaseAdapter {
    private ArrayList<SectionAdapter> mSections = new ArrayList<>();

    public interface SectionAdapter extends ListAdapter {
        public View getHeaderView(View convertView, ViewGroup parent);
    }

    public void clearSections() {
        mSections.clear();
        notifyDataSetChanged();
    }

    /**
     * After mutating sections, you <em>must</em>
     * {@link AdapterView#setAdapter(android.widget.Adapter)} to correctly
     * recount view types.
     */
    public void addSection(SectionAdapter adapter) {
        mSections.add(adapter);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        int count = 0;
        final int size = mSections.size();
        for (int i = 0; i < size; i++) {
            count += mSections.get(i).getCount() + 1;
        }
        return count;
    }

    @Override
    public Object getItem(int position) {
        final int size = mSections.size();
        for (int i = 0; i < size; i++) {
            final SectionAdapter section = mSections.get(i);
            final int sectionSize = section.getCount() + 1;

            // Check if position inside this section
            if (position == 0) {
                return section;
            } else if (position < sectionSize) {
                return section.getItem(position - 1);
            }

            // Otherwise jump into next section
            position -= sectionSize;
        }
        throw new IllegalStateException("Unknown position " + position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final int size = mSections.size();
        for (int i = 0; i < size; i++) {
            final SectionAdapter section = mSections.get(i);
            final int sectionSize = section.getCount() + 1;

            // Check if position inside this section
            if (position == 0) {
                return section.getHeaderView(convertView, parent);
            } else if (position < sectionSize) {
                return section.getView(position - 1, convertView, parent);
            }

            // Otherwise jump into next section
            position -= sectionSize;
        }
        throw new IllegalStateException("Unknown position " + position);
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        final int size = mSections.size();
        for (int i = 0; i < size; i++) {
            final SectionAdapter section = mSections.get(i);
            final int sectionSize = section.getCount() + 1;

            // Check if position inside this section
            if (position == 0) {
                return false;
            } else if (position < sectionSize) {
                return section.isEnabled(position - 1);
            }

            // Otherwise jump into next section
            position -= sectionSize;
        }
        throw new IllegalStateException("Unknown position " + position);
    }

    @Override
    public int getItemViewType(int position) {
        int type = 1;
        final int size = mSections.size();
        for (int i = 0; i < size; i++) {
            final SectionAdapter section = mSections.get(i);
            final int sectionSize = section.getCount() + 1;

            // Check if position inside this section
            if (position == 0) {
                return 0;
            } else if (position < sectionSize) {
                return type + section.getItemViewType(position - 1);
            }

            // Otherwise jump into next section
            position -= sectionSize;
            type += section.getViewTypeCount();
        }
        throw new IllegalStateException("Unknown position " + position);
    }

    @Override
    public int getViewTypeCount() {
        int count = 1;
        final int size = mSections.size();
        for (int i = 0; i < size; i++) {
            count += mSections.get(i).getViewTypeCount();
        }
        return count;
    }
}
