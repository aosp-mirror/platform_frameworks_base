package com.android.systemui.communal.widgets;

import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.IntentSender;
import android.os.UserHandle;
import android.widget.RemoteViews;
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel;
import java.util.List;

// Interface for the [GlanceableHubWidgetManagerService], which runs in a foreground user in HSUM
// and communicates with the headless system user.
interface IGlanceableHubWidgetManagerService {

    // Adds a listener for updates of Glanceable Hub widgets.
    oneway void addWidgetsListener(in IGlanceableHubWidgetsListener listener);

    // Removes a listener for updates of Glanceable Hub widgets.
    oneway void removeWidgetsListener(in IGlanceableHubWidgetsListener listener);

    // Sets a listener for updates on a specific widget.
    oneway void setAppWidgetHostListener(int appWidgetId, in IAppWidgetHostListener listener);

    // Requests to add a widget in the Glanceable Hub.
    oneway void addWidget(in ComponentName provider, in UserHandle user, int rank,
        in IConfigureWidgetCallback callback);

    // Requests to delete a widget from the Glanceable Hub.
    oneway void deleteWidget(int appWidgetId);

    // Requests to update the order of widgets in the Glanceable Hub.
    oneway void updateWidgetOrder(in int[] appWidgetIds, in int[] ranks);

    // Requests to resize a widget in the Glanceable Hub.
    oneway void resizeWidget(int appWidgetId, int spanY, in int[] appWidgetIds, in int[] ranks);

    // Returns the [IntentSender] for launching the configuration activity of the given widget.
    IntentSender getIntentSenderForConfigureActivity(int appWidgetId);

    // Listener for Glanceable Hub widget updates
    oneway interface IGlanceableHubWidgetsListener {
        // Called when widgets have updated.
        void onWidgetsUpdated(in List<CommunalWidgetContentModel> widgets);
    }

    // Mirrors [AppWidgetHost#AppWidgetHostListener].
    oneway interface IAppWidgetHostListener {
        void onUpdateProviderInfo(in @nullable AppWidgetProviderInfo appWidget);

        void updateAppWidget(in @nullable RemoteViews views);

        void updateAppWidgetDeferred(in String packageName, int appWidgetId);

        void onViewDataChanged(int viewId);
    }

    oneway interface IConfigureWidgetCallback {
        // Called when the given widget should launch its configuration activity. The caller should
        // report the result through the [IResultReceiver].
        void onConfigureWidget(int appWidgetId, in IResultReceiver resultReceiver);

        interface IResultReceiver {
            // Called when the widget configuration operation returns a result.
            void onResult(boolean success);
        }
    }
}
