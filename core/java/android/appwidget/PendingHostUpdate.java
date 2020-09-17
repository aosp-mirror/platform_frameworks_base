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
 * limitations under the License
 */

package android.appwidget;

import android.os.Parcel;
import android.os.Parcelable;
import android.widget.RemoteViews;

/**
 * @hide
 */
public class PendingHostUpdate implements Parcelable {

    static final int TYPE_VIEWS_UPDATE = 0;
    static final int TYPE_PROVIDER_CHANGED = 1;
    static final int TYPE_VIEW_DATA_CHANGED = 2;
    static final int TYPE_APP_WIDGET_REMOVED = 3;

    final int appWidgetId;
    final int type;
    RemoteViews views;
    AppWidgetProviderInfo widgetInfo;
    int viewId;

    public static PendingHostUpdate updateAppWidget(int appWidgetId, RemoteViews views) {
        PendingHostUpdate update = new PendingHostUpdate(appWidgetId, TYPE_VIEWS_UPDATE);
        update.views = views;
        return update;
    }

    public static PendingHostUpdate providerChanged(int appWidgetId, AppWidgetProviderInfo info) {
        PendingHostUpdate update = new PendingHostUpdate(appWidgetId, TYPE_PROVIDER_CHANGED);
        update.widgetInfo = info;
        return update;
    }

    public static PendingHostUpdate viewDataChanged(int appWidgetId, int viewId) {
        PendingHostUpdate update = new PendingHostUpdate(appWidgetId, TYPE_VIEW_DATA_CHANGED);
        update.viewId = viewId;
        return update;
    }

    /**
     * IAppWidgetHost appWidgetRemoved implimentaion
     */
    public static PendingHostUpdate appWidgetRemoved(int appWidgetId) {
        return new PendingHostUpdate(appWidgetId, TYPE_APP_WIDGET_REMOVED);
    }

    private PendingHostUpdate(int appWidgetId, int type) {
        this.appWidgetId = appWidgetId;
        this.type = type;
    }

    private PendingHostUpdate(Parcel in) {
        appWidgetId = in.readInt();
        type = in.readInt();

        switch (type) {
            case TYPE_VIEWS_UPDATE:
                if (0 != in.readInt()) {
                    views = new RemoteViews(in);
                }
                break;
            case TYPE_PROVIDER_CHANGED:
                if (0 != in.readInt()) {
                    widgetInfo = new AppWidgetProviderInfo(in);
                }
                break;
            case TYPE_VIEW_DATA_CHANGED:
                viewId = in.readInt();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(appWidgetId);
        dest.writeInt(type);
        switch (type) {
            case TYPE_VIEWS_UPDATE:
                writeNullParcelable(views, dest, flags);
                break;
            case TYPE_PROVIDER_CHANGED:
                writeNullParcelable(widgetInfo, dest, flags);
                break;
            case TYPE_VIEW_DATA_CHANGED:
                dest.writeInt(viewId);
                break;
        }
    }

    private void writeNullParcelable(Parcelable p, Parcel dest, int flags) {
        if (p != null) {
            dest.writeInt(1);
            p.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
    }

    /**
     * Parcelable.Creator that instantiates PendingHostUpdate objects
     */
    public static final @android.annotation.NonNull Parcelable.Creator<PendingHostUpdate> CREATOR
            = new Parcelable.Creator<PendingHostUpdate>() {
        public PendingHostUpdate createFromParcel(Parcel parcel) {
            return new PendingHostUpdate(parcel);
        }

        public PendingHostUpdate[] newArray(int size) {
            return new PendingHostUpdate[size];
        }
    };
}
