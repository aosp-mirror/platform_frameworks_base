/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.os.Trace;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.PagerAdapter;
import com.android.internal.widget.ViewPager;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Skeletal {@link PagerAdapter} implementation of a work or personal profile page for
 * intent resolution (including share sheet).
 */
public abstract class AbstractMultiProfilePagerAdapter extends PagerAdapter {

    private static final String TAG = "AbstractMultiProfilePagerAdapter";
    static final int PROFILE_PERSONAL = 0;
    static final int PROFILE_WORK = 1;

    @IntDef({PROFILE_PERSONAL, PROFILE_WORK})
    @interface Profile {}

    private final Context mContext;
    private int mCurrentPage;
    private OnProfileSelectedListener mOnProfileSelectedListener;
    private Set<Integer> mLoadedPages;
    private final EmptyStateProvider mEmptyStateProvider;
    private final UserHandle mWorkProfileUserHandle;
    private final UserHandle mCloneUserHandle;
    private final QuietModeManager mQuietModeManager;

    AbstractMultiProfilePagerAdapter(Context context, int currentPage,
            EmptyStateProvider emptyStateProvider,
            QuietModeManager quietModeManager,
            UserHandle workProfileUserHandle,
            UserHandle cloneUserHandle) {
        mContext = Objects.requireNonNull(context);
        mCurrentPage = currentPage;
        mLoadedPages = new HashSet<>();
        mWorkProfileUserHandle = workProfileUserHandle;
        mCloneUserHandle = cloneUserHandle;
        mEmptyStateProvider = emptyStateProvider;
        mQuietModeManager = quietModeManager;
    }

    private boolean isQuietModeEnabled(UserHandle workProfileUserHandle) {
        return mQuietModeManager.isQuietModeEnabled(workProfileUserHandle);
    }

    void setOnProfileSelectedListener(OnProfileSelectedListener listener) {
        mOnProfileSelectedListener = listener;
    }

    Context getContext() {
        return mContext;
    }

    /**
     * Sets this instance of this class as {@link ViewPager}'s {@link PagerAdapter} and sets
     * an {@link ViewPager.OnPageChangeListener} where it keeps track of the currently displayed
     * page and rebuilds the list.
     */
    void setupViewPager(ViewPager viewPager) {
        viewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mCurrentPage = position;
                if (!mLoadedPages.contains(position)) {
                    rebuildActiveTab(true);
                    mLoadedPages.add(position);
                }
                if (mOnProfileSelectedListener != null) {
                    mOnProfileSelectedListener.onProfileSelected(position);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (mOnProfileSelectedListener != null) {
                    mOnProfileSelectedListener.onProfilePageStateChanged(state);
                }
            }
        });
        viewPager.setAdapter(this);
        viewPager.setCurrentItem(mCurrentPage);
        mLoadedPages.add(mCurrentPage);
    }

    void clearInactiveProfileCache() {
        if (mLoadedPages.size() == 1) {
            return;
        }
        mLoadedPages.remove(1 - mCurrentPage);
    }

    @Override
    public ViewGroup instantiateItem(ViewGroup container, int position) {
        final ProfileDescriptor profileDescriptor = getItem(position);
        container.addView(profileDescriptor.rootView);
        return profileDescriptor.rootView;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object view) {
        container.removeView((View) view);
    }

    @Override
    public int getCount() {
        return getItemCount();
    }

    protected int getCurrentPage() {
        return mCurrentPage;
    }

    @VisibleForTesting
    public UserHandle getCurrentUserHandle() {
        return getActiveListAdapter().mResolverListController.getUserHandle();
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return null;
    }

    public UserHandle getCloneUserHandle() {
        return mCloneUserHandle;
    }

    /**
     * Returns the {@link ProfileDescriptor} relevant to the given <code>pageIndex</code>.
     * <ul>
     * <li>For a device with only one user, <code>pageIndex</code> value of
     * <code>0</code> would return the personal profile {@link ProfileDescriptor}.</li>
     * <li>For a device with a work profile, <code>pageIndex</code> value of <code>0</code> would
     * return the personal profile {@link ProfileDescriptor}, and <code>pageIndex</code> value of
     * <code>1</code> would return the work profile {@link ProfileDescriptor}.</li>
     * </ul>
     */
    public abstract ProfileDescriptor getItem(int pageIndex);

    /**
     * Returns the number of {@link ProfileDescriptor} objects.
     * <p>For a normal consumer device with only one user returns <code>1</code>.
     * <p>For a device with a work profile returns <code>2</code>.
     */
    abstract int getItemCount();

    /**
     * Performs view-related initialization procedures for the adapter specified
     * by <code>pageIndex</code>.
     */
    abstract void setupListAdapter(int pageIndex);

    /**
     * Returns the adapter of the list view for the relevant page specified by
     * <code>pageIndex</code>.
     * <p>This method is meant to be implemented with an implementation-specific return type
     * depending on the adapter type.
     */
    @VisibleForTesting
    public abstract Object getAdapterForIndex(int pageIndex);

    /**
     * Returns the {@link ResolverListAdapter} instance of the profile that represents
     * <code>userHandle</code>. If there is no such adapter for the specified
     * <code>userHandle</code>, returns {@code null}.
     * <p>For example, if there is a work profile on the device with user id 10, calling this method
     * with <code>UserHandle.of(10)</code> returns the work profile {@link ResolverListAdapter}.
     */
    @Nullable
    abstract ResolverListAdapter getListAdapterForUserHandle(UserHandle userHandle);

    /**
     * Returns the {@link ResolverListAdapter} instance of the profile that is currently visible
     * to the user.
     * <p>For example, if the user is viewing the work tab in the share sheet, this method returns
     * the work profile {@link ResolverListAdapter}.
     * @see #getInactiveListAdapter()
     */
    @VisibleForTesting
    public abstract ResolverListAdapter getActiveListAdapter();

    /**
     * If this is a device with a work profile, returns the {@link ResolverListAdapter} instance
     * of the profile that is <b><i>not</i></b> currently visible to the user. Otherwise returns
     * {@code null}.
     * <p>For example, if the user is viewing the work tab in the share sheet, this method returns
     * the personal profile {@link ResolverListAdapter}.
     * @see #getActiveListAdapter()
     */
    @VisibleForTesting
    public abstract @Nullable ResolverListAdapter getInactiveListAdapter();

    public abstract ResolverListAdapter getPersonalListAdapter();

    public abstract @Nullable ResolverListAdapter getWorkListAdapter();

    abstract Object getCurrentRootAdapter();

    abstract ViewGroup getActiveAdapterView();

    abstract @Nullable ViewGroup getInactiveAdapterView();

    /**
     * Rebuilds the tab that is currently visible to the user.
     * <p>Returns {@code true} if rebuild has completed.
     */
    boolean rebuildActiveTab(boolean doPostProcessing) {
        Trace.beginSection("MultiProfilePagerAdapter#rebuildActiveTab");
        boolean result = rebuildTab(getActiveListAdapter(), doPostProcessing);
        Trace.endSection();
        return result;
    }

    /**
     * Rebuilds the tab that is not currently visible to the user, if such one exists.
     * <p>Returns {@code true} if rebuild has completed.
     */
    boolean rebuildInactiveTab(boolean doPostProcessing) {
        Trace.beginSection("MultiProfilePagerAdapter#rebuildInactiveTab");
        if (getItemCount() == 1) {
            Trace.endSection();
            return false;
        }
        boolean result = rebuildTab(getInactiveListAdapter(), doPostProcessing);
        Trace.endSection();
        return result;
    }

    private int userHandleToPageIndex(UserHandle userHandle) {
        if (userHandle.equals(getPersonalListAdapter().mResolverListController.getUserHandle())) {
            return PROFILE_PERSONAL;
        } else {
            return PROFILE_WORK;
        }
    }

    private boolean rebuildTab(ResolverListAdapter activeListAdapter, boolean doPostProcessing) {
        if (shouldSkipRebuild(activeListAdapter)) {
            activeListAdapter.postListReadyRunnable(doPostProcessing, /* rebuildCompleted */ true);
            return false;
        }
        return activeListAdapter.rebuildList(doPostProcessing);
    }

    private boolean shouldSkipRebuild(ResolverListAdapter activeListAdapter) {
        EmptyState emptyState = mEmptyStateProvider.getEmptyState(activeListAdapter);
        return emptyState != null && emptyState.shouldSkipDataRebuild();
    }

    /**
     * The empty state screens are shown according to their priority:
     * <ol>
     * <li>(highest priority) cross-profile disabled by policy (handled in
     * {@link #rebuildTab(ResolverListAdapter, boolean)})</li>
     * <li>no apps available</li>
     * <li>(least priority) work is off</li>
     * </ol>
     *
     * The intention is to prevent the user from having to turn
     * the work profile on if there will not be any apps resolved
     * anyway.
     */
    void showEmptyResolverListEmptyState(ResolverListAdapter listAdapter) {
        final EmptyState emptyState = mEmptyStateProvider.getEmptyState(listAdapter);

        if (emptyState == null) {
            return;
        }

        emptyState.onEmptyStateShown();

        View.OnClickListener clickListener = null;

        if (emptyState.getButtonClickListener() != null) {
            clickListener = v -> emptyState.getButtonClickListener().onClick(() -> {
                ProfileDescriptor descriptor = getItem(
                        userHandleToPageIndex(listAdapter.getUserHandle()));
                AbstractMultiProfilePagerAdapter.this.showSpinner(descriptor.getEmptyStateView());
            });
        }

        showEmptyState(listAdapter, emptyState, clickListener);
    }

    /**
     * Class to get user id of the current process
     */
    public static class MyUserIdProvider {
        /**
         * @return user id of the current process
         */
        public int getMyUserId() {
            return UserHandle.myUserId();
        }
    }

    /**
     * Utility class to check if there are cross profile intents, it is in a separate class so
     * it could be mocked in tests
     */
    public static class CrossProfileIntentsChecker {

        private final ContentResolver mContentResolver;

        public CrossProfileIntentsChecker(@NonNull ContentResolver contentResolver) {
            mContentResolver = contentResolver;
        }

        /**
         * Returns {@code true} if at least one of the provided {@code intents} can be forwarded
         * from {@code source} (user id) to {@code target} (user id).
         */
        public boolean hasCrossProfileIntents(List<Intent> intents, @UserIdInt int source,
                @UserIdInt int target) {
            IPackageManager packageManager = AppGlobals.getPackageManager();

            return intents.stream().anyMatch(intent ->
                    null != IntentForwarderActivity.canForward(intent, source, target,
                            packageManager, mContentResolver));
        }
    }

    protected void showEmptyState(ResolverListAdapter activeListAdapter, EmptyState emptyState,
            View.OnClickListener buttonOnClick) {
        ProfileDescriptor descriptor = getItem(
                userHandleToPageIndex(activeListAdapter.getUserHandle()));
        descriptor.rootView.findViewById(R.id.resolver_list).setVisibility(View.GONE);
        ViewGroup emptyStateView = descriptor.getEmptyStateView();
        resetViewVisibilitiesForEmptyState(emptyStateView);
        emptyStateView.setVisibility(View.VISIBLE);

        View container = emptyStateView.findViewById(R.id.resolver_empty_state_container);
        setupContainerPadding(container);

        TextView titleView = emptyStateView.findViewById(R.id.resolver_empty_state_title);
        String title = emptyState.getTitle();
        if (title != null) {
            titleView.setVisibility(View.VISIBLE);
            titleView.setText(title);
        } else {
            titleView.setVisibility(View.GONE);
        }

        TextView subtitleView = emptyStateView.findViewById(R.id.resolver_empty_state_subtitle);
        String subtitle = emptyState.getSubtitle();
        if (subtitle != null) {
            subtitleView.setVisibility(View.VISIBLE);
            subtitleView.setText(subtitle);
        } else {
            subtitleView.setVisibility(View.GONE);
        }

        View defaultEmptyText = emptyStateView.findViewById(R.id.empty);
        defaultEmptyText.setVisibility(emptyState.useDefaultEmptyView() ? View.VISIBLE : View.GONE);

        Button button = emptyStateView.findViewById(R.id.resolver_empty_state_button);
        button.setVisibility(buttonOnClick != null ? View.VISIBLE : View.GONE);
        button.setOnClickListener(buttonOnClick);

        activeListAdapter.markTabLoaded();
    }

    /**
     * Sets up the padding of the view containing the empty state screens.
     * <p>This method is meant to be overridden so that subclasses can customize the padding.
     */
    protected void setupContainerPadding(View container) {}

    private void showSpinner(View emptyStateView) {
        emptyStateView.findViewById(R.id.resolver_empty_state_title).setVisibility(View.INVISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_button).setVisibility(View.INVISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_progress).setVisibility(View.VISIBLE);
        emptyStateView.findViewById(R.id.empty).setVisibility(View.GONE);
    }

    private void resetViewVisibilitiesForEmptyState(View emptyStateView) {
        emptyStateView.findViewById(R.id.resolver_empty_state_title).setVisibility(View.VISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_subtitle).setVisibility(View.VISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_button).setVisibility(View.INVISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_progress).setVisibility(View.GONE);
        emptyStateView.findViewById(R.id.empty).setVisibility(View.GONE);
    }

    protected void showListView(ResolverListAdapter activeListAdapter) {
        ProfileDescriptor descriptor = getItem(
                userHandleToPageIndex(activeListAdapter.getUserHandle()));
        descriptor.rootView.findViewById(R.id.resolver_list).setVisibility(View.VISIBLE);
        View emptyStateView = descriptor.rootView.findViewById(R.id.resolver_empty_state);
        emptyStateView.setVisibility(View.GONE);
    }

    boolean shouldShowEmptyStateScreen(ResolverListAdapter listAdapter) {
        int count = listAdapter.getUnfilteredCount();
        return (count == 0 && listAdapter.getPlaceholderCount() == 0)
                || (listAdapter.getUserHandle().equals(mWorkProfileUserHandle)
                    && isQuietModeEnabled(mWorkProfileUserHandle));
    }

    public static class ProfileDescriptor {
        public final ViewGroup rootView;
        private final ViewGroup mEmptyStateView;
        ProfileDescriptor(ViewGroup rootView) {
            this.rootView = rootView;
            mEmptyStateView = rootView.findViewById(R.id.resolver_empty_state);
        }

        protected ViewGroup getEmptyStateView() {
            return mEmptyStateView;
        }
    }

    public interface OnProfileSelectedListener {
        /**
         * Callback for when the user changes the active tab from personal to work or vice versa.
         * <p>This callback is only called when the intent resolver or share sheet shows
         * the work and personal profiles.
         * @param profileIndex {@link #PROFILE_PERSONAL} if the personal profile was selected or
         * {@link #PROFILE_WORK} if the work profile was selected.
         */
        void onProfileSelected(int profileIndex);


        /**
         * Callback for when the scroll state changes. Useful for discovering when the user begins
         * dragging, when the pager is automatically settling to the current page, or when it is
         * fully stopped/idle.
         * @param state {@link ViewPager#SCROLL_STATE_IDLE}, {@link ViewPager#SCROLL_STATE_DRAGGING}
         *              or {@link ViewPager#SCROLL_STATE_SETTLING}
         * @see ViewPager.OnPageChangeListener#onPageScrollStateChanged
         */
        void onProfilePageStateChanged(int state);
    }

    /**
     * Returns an empty state to show for the current profile page (tab) if necessary.
     * This could be used e.g. to show a blocker on a tab if device management policy doesn't
     * allow to use it or there are no apps available.
     */
    public interface EmptyStateProvider {
        /**
         * When a non-null empty state is returned the corresponding profile page will show
         * this empty state
         * @param resolverListAdapter the current adapter
         */
        @Nullable
        default EmptyState getEmptyState(ResolverListAdapter resolverListAdapter) {
            return null;
        }
    }

    /**
     * Empty state provider that combines multiple providers. Providers earlier in the list have
     * priority, that is if there is a provider that returns non-null empty state then all further
     * providers will be ignored.
     */
    public static class CompositeEmptyStateProvider implements EmptyStateProvider {

        private final EmptyStateProvider[] mProviders;

        public CompositeEmptyStateProvider(EmptyStateProvider... providers) {
            mProviders = providers;
        }

        @Nullable
        @Override
        public EmptyState getEmptyState(ResolverListAdapter resolverListAdapter) {
            for (EmptyStateProvider provider : mProviders) {
                EmptyState emptyState = provider.getEmptyState(resolverListAdapter);
                if (emptyState != null) {
                    return emptyState;
                }
            }
            return null;
        }
    }

    /**
     * Describes how the blocked empty state should look like for a profile tab
     */
    public interface EmptyState {
        /**
         * Title that will be shown on the empty state
         */
        @Nullable
        default String getTitle() { return null; }

        /**
         * Subtitle that will be shown underneath the title on the empty state
         */
        @Nullable
        default String getSubtitle()  { return null; }

        /**
         * If non-null then a button will be shown and this listener will be called
         * when the button is clicked
         */
        @Nullable
        default ClickListener getButtonClickListener()  { return null; }

        /**
         * If true then default text ('No apps can perform this action') and style for the empty
         * state will be applied, title and subtitle will be ignored.
         */
        default boolean useDefaultEmptyView() { return false; }

        /**
         * Returns true if for this empty state we should skip rebuilding of the apps list
         * for this tab.
         */
        default boolean shouldSkipDataRebuild() { return false; }

        /**
         * Called when empty state is shown, could be used e.g. to track analytics events
         */
        default void onEmptyStateShown() {}

        interface ClickListener {
            void onClick(TabControl currentTab);
        }

        interface TabControl {
            void showSpinner();
        }
    }

    /**
     * Listener for when the user switches on the work profile from the work tab.
     */
    interface OnSwitchOnWorkSelectedListener {
        /**
         * Callback for when the user switches on the work profile from the work tab.
         */
        void onSwitchOnWorkSelected();
    }

    /**
     * Describes an injector to be used for cross profile functionality. Overridable for testing.
     */
    public interface QuietModeManager {
        /**
         * Returns whether the given profile is in quiet mode or not.
         */
        boolean isQuietModeEnabled(UserHandle workProfileUserHandle);

        /**
         * Enables or disables quiet mode for a managed profile.
         */
        void requestQuietModeEnabled(boolean enabled, UserHandle workProfileUserHandle);

        /**
         * Should be called when the work profile enabled broadcast received
         */
        void markWorkProfileEnabledBroadcastReceived();

        /**
         * Returns true if enabling of work profile is in progress
         */
        boolean isWaitingToEnableWorkProfile();
    }
}