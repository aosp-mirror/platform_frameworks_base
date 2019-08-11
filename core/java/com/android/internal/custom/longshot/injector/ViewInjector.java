package com.android.internal.custom.longshot.injector;

import android.content.Context;

import com.android.internal.custom.longshot.LongScreenshotManager;

import java.util.ArrayList;
import java.util.List;

public class ViewInjector {

    public static class View {

        private static final List<Element> ELEMENTS_NOOVERSCROLL = new ArrayList();
        private static final List<Element> ELEMENTS_NOSCROLL = new ArrayList();
        private static final List<Element> ELEMENTS_OVERSCROLL = new ArrayList();
        private static final List<Element> ELEMENTS_SCROLL = new ArrayList();
        private static final String TAG = "ViewInjector";
        public static boolean isInjection = false;

        private enum Element {
            SCROLL(5, "AbsListView.trackMotionScroll"),
            QQSCROLL(7, "tencent.widget.AbsListView.onTouchEvent"),
            MMAWAKEN12(12, "tencent.mm.ui.base.MMPullDownView.dispatchTouchEvent"),
            MMAWAKEN14(14, "tencent.mm.ui.base.MMPullDownView.dispatchTouchEvent"),
            MMAWAKEN15(15, "tencent.mm.ui.base.MMPullDownView.dispatchTouchEvent"),
            OVERSCROLL(5, "AbsListView.onOverScrolled"),
            CONTENTSCROLL(4, "ContentView.onScrollChanged"),
            MMCHANGE9(9, "tencent.mm.ui.base.MMPullDownView.dispatchTouchEvent"),
            MMCHANGE12(12, "tencent.mm.ui.base.MMPullDownView.dispatchTouchEvent"),
            MMCHANGE14(14, "tencent.mm.ui.base.MMPullDownView.dispatchTouchEvent"),
            MMCHANGE15(15, "tencent.mm.ui.base.MMPullDownView.dispatchTouchEvent"),
            BROWSERSCROLL(14, "oppo.browser.navigation.widget.NavigationView.dispatchTouchEvent"),
            QZONESCROLL(8, "qzone.widget.QZonePullToRefreshListView.onScrollChanged"),
            WEBSCROLL(16, "WebView$PrivateAccess.overScrollBy"),
            LISTOVERSCROLL(6, "AbsListView.onTouchEvent"),
            WEBOVERSCROLL(5, "WebView$PrivateAccess.overScrollBy"),
            BROWSEROVERSCROLL(11, "oppo.browser.navigation.widget.NavigationView.dispatchTouchEvent");

            private String mName;
            private int mPosition;

            private Element(int position, String name) {
                this.mName = null;
                this.mPosition = -1;
                this.mPosition = position;
                this.mName = name;
            }

            public int getPosition() {
                return this.mPosition;
            }

            public String getName() {
                return this.mName;
            }

            public String getNameString() {
                StringBuilder sb = new StringBuilder();
                sb.append(".");
                sb.append(getName());
                sb.append("(");
                return sb.toString();
            }
        }

        public static void onUnscrollableView(Context context) {
            if (isInjection) {
                LongScreenshotManager sm = (LongScreenshotManager) context.getSystemService(Context.LONGSCREENSHOT_SERVICE);
                if (sm != null) {
                    sm.onUnscrollableView();
                }
            }
        }

        public static void setScrolledViewTop(Context context, int top) {
            if (isInjection) {
                LongScreenshotManager sm = (LongScreenshotManager) context.getSystemService(Context.LONGSCREENSHOT_SERVICE);
                if (sm != null) {
                    sm.notifyScrollViewTop(top);
                }
            }
        }

        public static void onOverScrolled(Context context, boolean isOverScroll) {
            if (isInjection) {
                LongScreenshotManager sm = (LongScreenshotManager) context.getSystemService(Context.LONGSCREENSHOT_SERVICE);
                if (sm != null && sm.isLongshotMoveState()) {
                    StackTraceElement[] stacks = Thread.currentThread().getStackTrace();
                    ELEMENTS_OVERSCROLL.add(Element.LISTOVERSCROLL);
                    ELEMENTS_NOOVERSCROLL.add(Element.WEBOVERSCROLL);
                    ELEMENTS_NOOVERSCROLL.add(Element.BROWSERSCROLL);
                    ELEMENTS_NOOVERSCROLL.add(Element.BROWSEROVERSCROLL);
                    if (!isElement(stacks, ELEMENTS_NOOVERSCROLL)) {
                        if (isElement(stacks, ELEMENTS_OVERSCROLL)) {
                            sm.notifyLongshotScroll(true);
                        } else {
                            sm.notifyLongshotScroll(false);
                        }
                    }
                    clearElements();
                }
            }
        }

        public static void onScrollChanged(Context context, boolean canScrollVertically) {
            if (isInjection) {
                LongScreenshotManager sm = (LongScreenshotManager) context.getSystemService(Context.LONGSCREENSHOT_SERVICE);
                if (sm != null && sm.isLongshotMoveState()) {
                    StackTraceElement[] stacks = Thread.currentThread().getStackTrace();
                    ELEMENTS_NOSCROLL.add(Element.MMCHANGE9);
                    ELEMENTS_NOSCROLL.add(Element.MMCHANGE12);
                    ELEMENTS_NOSCROLL.add(Element.MMCHANGE14);
                    ELEMENTS_NOSCROLL.add(Element.MMCHANGE15);
                    ELEMENTS_NOSCROLL.add(Element.CONTENTSCROLL);
                    ELEMENTS_NOSCROLL.add(Element.BROWSERSCROLL);
                    ELEMENTS_NOSCROLL.add(Element.QZONESCROLL);
                    ELEMENTS_NOSCROLL.add(Element.WEBSCROLL);
                    if (!isElement(stacks, ELEMENTS_NOSCROLL)) {
                        if (!canScrollVertically) {
                            sm.notifyLongshotScroll(true);
                        } else {
                            sm.notifyLongshotScroll(false);
                        }
                    }
                    clearElements();
                }
            }
        }

        public static boolean onAwakenScrollBars(Context context) {
            if (!isInjection) {
                return false;
            }
            boolean result = false;
            LongScreenshotManager sm = (LongScreenshotManager) context.getSystemService(Context.LONGSCREENSHOT_SERVICE);
            if (sm != null) {
                result = sm.isLongshotMoveState();
                if (result) {
                    StackTraceElement[] stacks = Thread.currentThread().getStackTrace();
                    ELEMENTS_OVERSCROLL.add(Element.OVERSCROLL);
                    ELEMENTS_NOSCROLL.add(Element.MMAWAKEN12);
                    ELEMENTS_NOSCROLL.add(Element.MMAWAKEN14);
                    ELEMENTS_NOSCROLL.add(Element.MMAWAKEN15);
                    ELEMENTS_SCROLL.add(Element.QQSCROLL);
                    ELEMENTS_SCROLL.add(Element.SCROLL);
                    if (!isElement(stacks, ELEMENTS_NOSCROLL)) {
                        if (isElement(stacks, ELEMENTS_OVERSCROLL)) {
                            sm.notifyLongshotScroll(true);
                        } else if (isElement(stacks, ELEMENTS_SCROLL)) {
                            sm.notifyLongshotScroll(false);
                        }
                    }
                    clearElements();
                }
            }
            return result;
        }

        private static boolean isElement(StackTraceElement[] stacks, List<Element> elements) {
            boolean result = false;
            for (Element element : elements) {
                int pos = element.getPosition();
                if (stacks.length > pos) {
                    result = stacks[pos].toString().contains(element.getNameString());
                    if (result) {
                        break;
                    }
                }
            }
            return result;
        }

        private static void clearElements() {
            ELEMENTS_SCROLL.clear();
            ELEMENTS_NOSCROLL.clear();
            ELEMENTS_OVERSCROLL.clear();
            ELEMENTS_NOOVERSCROLL.clear();
        }
    }
}
