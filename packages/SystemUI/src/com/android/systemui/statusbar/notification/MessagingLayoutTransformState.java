/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.content.res.Resources;
import android.util.Pools;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.widget.MessagingGroup;
import com.android.internal.widget.MessagingImageMessage;
import com.android.internal.widget.MessagingLayout;
import com.android.internal.widget.MessagingLinearLayout;
import com.android.internal.widget.MessagingMessage;
import com.android.internal.widget.MessagingPropertyAnimator;
import com.android.systemui.Interpolators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A transform state of the action list
*/
public class MessagingLayoutTransformState extends TransformState {

    private static Pools.SimplePool<MessagingLayoutTransformState> sInstancePool
            = new Pools.SimplePool<>(40);
    private MessagingLinearLayout mMessageContainer;
    private MessagingLayout mMessagingLayout;
    private HashMap<MessagingGroup, MessagingGroup> mGroupMap = new HashMap<>();
    private float mRelativeTranslationOffset;

    public static MessagingLayoutTransformState obtain() {
        MessagingLayoutTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new MessagingLayoutTransformState();
    }

    @Override
    public void initFrom(View view, TransformInfo transformInfo) {
        super.initFrom(view, transformInfo);
        if (mTransformedView instanceof MessagingLinearLayout) {
            mMessageContainer = (MessagingLinearLayout) mTransformedView;
            mMessagingLayout = mMessageContainer.getMessagingLayout();
            Resources resources = view.getContext().getResources();
            mRelativeTranslationOffset = resources.getDisplayMetrics().density * 8;
        }
    }

    @Override
    public boolean transformViewTo(TransformState otherState, float transformationAmount) {
        if (otherState instanceof MessagingLayoutTransformState) {
            // It's a party! Let's transform between these two layouts!
            transformViewInternal((MessagingLayoutTransformState) otherState, transformationAmount,
                    true /* to */);
            return true;
        } else {
            return super.transformViewTo(otherState, transformationAmount);
        }
    }

    @Override
    public void transformViewFrom(TransformState otherState, float transformationAmount) {
        if (otherState instanceof MessagingLayoutTransformState) {
            // It's a party! Let's transform between these two layouts!
            transformViewInternal((MessagingLayoutTransformState) otherState, transformationAmount,
                    false /* to */);
        } else {
            super.transformViewFrom(otherState, transformationAmount);
        }
    }

    private void transformViewInternal(MessagingLayoutTransformState mlt,
            float transformationAmount, boolean to) {
        ensureVisible();
        ArrayList<MessagingGroup> ownGroups = filterHiddenGroups(
                mMessagingLayout.getMessagingGroups());
        ArrayList<MessagingGroup> otherGroups = filterHiddenGroups(
                mlt.mMessagingLayout.getMessagingGroups());
        HashMap<MessagingGroup, MessagingGroup> pairs = findPairs(ownGroups, otherGroups);
        MessagingGroup lastPairedGroup = null;
        float currentTranslation = 0;
        float transformationDistanceRemaining = 0;
        for (int i = ownGroups.size() - 1; i >= 0; i--) {
            MessagingGroup ownGroup = ownGroups.get(i);
            MessagingGroup matchingGroup = pairs.get(ownGroup);
            if (!isGone(ownGroup)) {
                if (matchingGroup != null) {
                    transformGroups(ownGroup, matchingGroup, transformationAmount, to);
                    if (lastPairedGroup == null) {
                        lastPairedGroup = ownGroup;
                        if (to){
                            float totalTranslation = ownGroup.getTop() - matchingGroup.getTop();
                            transformationDistanceRemaining
                                    = matchingGroup.getAvatar().getTranslationY();
                            currentTranslation = transformationDistanceRemaining - totalTranslation;
                        } else {
                            float totalTranslation = matchingGroup.getTop() - ownGroup.getTop();
                            currentTranslation = ownGroup.getAvatar().getTranslationY();
                            transformationDistanceRemaining = currentTranslation - totalTranslation;
                        }
                    }
                } else {
                    float groupTransformationAmount = transformationAmount;
                    if (lastPairedGroup != null) {
                        adaptGroupAppear(ownGroup, transformationAmount, currentTranslation,
                                to);
                        int distance = lastPairedGroup.getTop() - ownGroup.getTop();
                        float transformationDistance = mTransformInfo.isAnimating()
                                ? distance
                                : ownGroup.getHeight() * 0.75f;
                        float translationProgress = transformationDistanceRemaining
                                - (distance - transformationDistance);
                        groupTransformationAmount =
                                translationProgress / transformationDistance;
                        groupTransformationAmount = Math.max(0.0f, Math.min(1.0f,
                                groupTransformationAmount));
                        if (to) {
                            groupTransformationAmount = 1.0f - groupTransformationAmount;
                        }
                    }
                    if (to) {
                        disappear(ownGroup, groupTransformationAmount);
                    } else {
                        appear(ownGroup, groupTransformationAmount);
                    }
                }
            }
        }
    }

    private void appear(MessagingGroup ownGroup, float transformationAmount) {
        MessagingLinearLayout ownMessages = ownGroup.getMessageContainer();
        for (int j = 0; j < ownMessages.getChildCount(); j++) {
            View child = ownMessages.getChildAt(j);
            if (isGone(child)) {
                continue;
            }
            appear(child, transformationAmount);
            setClippingDeactivated(child, true);
        }
        appear(ownGroup.getAvatar(), transformationAmount);
        appear(ownGroup.getSenderView(), transformationAmount);
        appear(ownGroup.getIsolatedMessage(), transformationAmount);
        setClippingDeactivated(ownGroup.getSenderView(), true);
        setClippingDeactivated(ownGroup.getAvatar(), true);
    }

    private void adaptGroupAppear(MessagingGroup ownGroup, float transformationAmount,
            float overallTranslation, boolean to) {
        float relativeOffset;
        if (to) {
            relativeOffset = transformationAmount * mRelativeTranslationOffset;
        } else {
            relativeOffset = (1.0f - transformationAmount) * mRelativeTranslationOffset;
        }
        if (ownGroup.getSenderView().getVisibility() != View.GONE) {
            relativeOffset *= 0.5f;
        }
        ownGroup.getMessageContainer().setTranslationY(relativeOffset);
        ownGroup.setTranslationY(overallTranslation * 0.85f);
    }

    private void disappear(MessagingGroup ownGroup, float transformationAmount) {
        MessagingLinearLayout ownMessages = ownGroup.getMessageContainer();
        for (int j = 0; j < ownMessages.getChildCount(); j++) {
            View child = ownMessages.getChildAt(j);
            if (isGone(child)) {
                continue;
            }
            disappear(child, transformationAmount);
            setClippingDeactivated(child, true);
        }
        disappear(ownGroup.getAvatar(), transformationAmount);
        disappear(ownGroup.getSenderView(), transformationAmount);
        disappear(ownGroup.getIsolatedMessage(), transformationAmount);
        setClippingDeactivated(ownGroup.getSenderView(), true);
        setClippingDeactivated(ownGroup.getAvatar(), true);
    }

    private void appear(View child, float transformationAmount) {
        if (child == null || child.getVisibility() == View.GONE) {
            return;
        }
        TransformState ownState = TransformState.createFrom(child, mTransformInfo);
        ownState.appear(transformationAmount, null);
        ownState.recycle();
    }

    private void disappear(View child, float transformationAmount) {
        if (child == null || child.getVisibility() == View.GONE) {
            return;
        }
        TransformState ownState = TransformState.createFrom(child, mTransformInfo);
        ownState.disappear(transformationAmount, null);
        ownState.recycle();
    }

    private ArrayList<MessagingGroup> filterHiddenGroups(
            ArrayList<MessagingGroup> groups) {
        ArrayList<MessagingGroup> result = new ArrayList<>(groups);
        for (int i = 0; i < result.size(); i++) {
            MessagingGroup messagingGroup = result.get(i);
            if (isGone(messagingGroup)) {
                result.remove(i);
                i--;
            }
        }
        return result;
    }

    private void transformGroups(MessagingGroup ownGroup, MessagingGroup otherGroup,
            float transformationAmount, boolean to) {
        boolean useLinearTransformation =
                otherGroup.getIsolatedMessage() == null && !mTransformInfo.isAnimating();
        transformView(transformationAmount, to, ownGroup.getSenderView(), otherGroup.getSenderView(),
                true /* sameAsAny */, useLinearTransformation);
        transformView(transformationAmount, to, ownGroup.getAvatar(), otherGroup.getAvatar(),
                true /* sameAsAny */, useLinearTransformation);
        List<MessagingMessage> ownMessages = ownGroup.getMessages();
        List<MessagingMessage> otherMessages = otherGroup.getMessages();
        float previousTranslation = 0;
        for (int i = 0; i < ownMessages.size(); i++) {
            View child = ownMessages.get(ownMessages.size() - 1 - i).getView();
            if (isGone(child)) {
                continue;
            }
            int otherIndex = otherMessages.size() - 1 - i;
            View otherChild = null;
            if (otherIndex >= 0) {
                otherChild = otherMessages.get(otherIndex).getView();
                if (isGone(otherChild)) {
                    otherChild = null;
                }
            }
            if (otherChild == null && previousTranslation < 0) {
                // Let's fade out as we approach the top of the screen. We can only do this if
                // we're actually moving up
                float distanceToTop = child.getTop() + child.getHeight() + previousTranslation;
                transformationAmount = distanceToTop / child.getHeight();
                transformationAmount = Math.max(0.0f, Math.min(1.0f, transformationAmount));
            }
            transformView(transformationAmount, to, child, otherChild, false, /* sameAsAny */
                    useLinearTransformation);
            boolean otherIsIsolated = otherGroup.getIsolatedMessage() == otherChild;
            if (transformationAmount == 0.0f && otherIsIsolated) {
                ownGroup.setTransformingImages(true);
            }
            if (otherChild == null) {
                child.setTranslationY(previousTranslation);
                setClippingDeactivated(child, true);
            } else if (ownGroup.getIsolatedMessage() == child || otherIsIsolated) {
                // We don't want to add any translation for the image that is transforming
            } else if (to) {
                float totalTranslation = child.getTop() + ownGroup.getTop()
                        - otherChild.getTop() - otherChild.getTop();
                previousTranslation = otherChild.getTranslationY() - totalTranslation;
            } else {
                previousTranslation = child.getTranslationY();
            }
        }
        ownGroup.updateClipRect();
    }

    private void transformView(float transformationAmount, boolean to, View ownView,
            View otherView, boolean sameAsAny, boolean useLinearTransformation) {
        TransformState ownState = TransformState.createFrom(ownView, mTransformInfo);
        if (useLinearTransformation) {
            ownState.setDefaultInterpolator(Interpolators.LINEAR);
        }
        ownState.setIsSameAsAnyView(sameAsAny);
        if (to) {
            if (otherView != null) {
                TransformState otherState = TransformState.createFrom(otherView, mTransformInfo);
                ownState.transformViewTo(otherState, transformationAmount);
                otherState.recycle();
            } else {
                ownState.disappear(transformationAmount, null);
            }
        } else {
            if (otherView != null) {
                TransformState otherState = TransformState.createFrom(otherView, mTransformInfo);
                ownState.transformViewFrom(otherState, transformationAmount);
                otherState.recycle();
            } else {
                ownState.appear(transformationAmount, null);
            }
        }
        ownState.recycle();
    }

    private HashMap<MessagingGroup, MessagingGroup> findPairs(ArrayList<MessagingGroup> ownGroups,
            ArrayList<MessagingGroup> otherGroups) {
        mGroupMap.clear();
        int lastMatch = Integer.MAX_VALUE;
        for (int i = ownGroups.size() - 1; i >= 0; i--) {
            MessagingGroup ownGroup = ownGroups.get(i);
            MessagingGroup bestMatch = null;
            int bestCompatibility = 0;
            for (int j = Math.min(otherGroups.size(), lastMatch) - 1; j >= 0; j--) {
                MessagingGroup otherGroup = otherGroups.get(j);
                int compatibility = ownGroup.calculateGroupCompatibility(otherGroup);
                if (compatibility > bestCompatibility) {
                    bestCompatibility = compatibility;
                    bestMatch = otherGroup;
                    lastMatch = j;
                }
            }
            if (bestMatch != null) {
                mGroupMap.put(ownGroup, bestMatch);
            }
        }
        return mGroupMap;
    }

    private boolean isGone(View view) {
        if (view.getVisibility() == View.GONE) {
            return true;
        }
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof MessagingLinearLayout.LayoutParams
                && ((MessagingLinearLayout.LayoutParams) lp).hide) {
            return true;
        }
        return false;
    }

    @Override
    public void setVisible(boolean visible, boolean force) {
        super.setVisible(visible, force);
        resetTransformedView();
        ArrayList<MessagingGroup> ownGroups = mMessagingLayout.getMessagingGroups();
        for (int i = 0; i < ownGroups.size(); i++) {
            MessagingGroup ownGroup = ownGroups.get(i);
            if (!isGone(ownGroup)) {
                MessagingLinearLayout ownMessages = ownGroup.getMessageContainer();
                for (int j = 0; j < ownMessages.getChildCount(); j++) {
                    View child = ownMessages.getChildAt(j);
                    setVisible(child, visible, force);
                }
                setVisible(ownGroup.getAvatar(), visible, force);
                setVisible(ownGroup.getSenderView(), visible, force);
                MessagingImageMessage isolatedMessage = ownGroup.getIsolatedMessage();
                if (isolatedMessage != null) {
                    setVisible(isolatedMessage, visible, force);
                }
            }
        }
    }

    private void setVisible(View child, boolean visible, boolean force) {
        if (isGone(child) || MessagingPropertyAnimator.isAnimatingAlpha(child)) {
            return;
        }
        TransformState ownState = TransformState.createFrom(child, mTransformInfo);
        ownState.setVisible(visible, force);
        ownState.recycle();
    }

    @Override
    protected void resetTransformedView() {
        super.resetTransformedView();
        ArrayList<MessagingGroup> ownGroups = mMessagingLayout.getMessagingGroups();
        for (int i = 0; i < ownGroups.size(); i++) {
            MessagingGroup ownGroup = ownGroups.get(i);
            if (!isGone(ownGroup)) {
                MessagingLinearLayout ownMessages = ownGroup.getMessageContainer();
                for (int j = 0; j < ownMessages.getChildCount(); j++) {
                    View child = ownMessages.getChildAt(j);
                    if (isGone(child)) {
                        continue;
                    }
                    resetTransformedView(child);
                    setClippingDeactivated(child, false);
                }
                resetTransformedView(ownGroup.getAvatar());
                resetTransformedView(ownGroup.getSenderView());
                MessagingImageMessage isolatedMessage = ownGroup.getIsolatedMessage();
                if (isolatedMessage != null) {
                    resetTransformedView(isolatedMessage);
                }
                setClippingDeactivated(ownGroup.getAvatar(), false);
                setClippingDeactivated(ownGroup.getSenderView(), false);
                ownGroup.setTranslationY(0);
                ownGroup.getMessageContainer().setTranslationY(0);
            }
            ownGroup.setTransformingImages(false);
            ownGroup.updateClipRect();
        }
    }

    @Override
    public void prepareFadeIn() {
        super.prepareFadeIn();
        setVisible(true /* visible */, false /* force */);
    }

    private void resetTransformedView(View child) {
        TransformState ownState = TransformState.createFrom(child, mTransformInfo);
        ownState.resetTransformedView();
        ownState.recycle();
    }

    @Override
    protected void reset() {
        super.reset();
        mMessageContainer = null;
        mMessagingLayout = null;
    }

    @Override
    public void recycle() {
        super.recycle();
        mGroupMap.clear();;
        sInstancePool.release(this);
    }
}
