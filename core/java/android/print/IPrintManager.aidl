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

package android.print;

import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.print.IPrinterDiscoveryObserver;
import android.print.IPrintDocumentAdapter;
import android.print.PrintJobId;
import android.print.IPrintJobStateChangeListener;
import android.print.IPrintServicesChangeListener;
import android.printservice.recommendation.IRecommendationsChangeListener;
import android.print.PrinterId;
import android.print.PrintJobInfo;
import android.print.PrintAttributes;
import android.printservice.recommendation.RecommendationInfo;
import android.printservice.PrintServiceInfo;

/**
 * Interface for communication with the core print manager service.
 *
 * @hide
 */
interface IPrintManager {
    List<PrintJobInfo> getPrintJobInfos(int appId, int userId);
    PrintJobInfo getPrintJobInfo(in PrintJobId printJobId, int appId, int userId);
    Bundle print(String printJobName, in IPrintDocumentAdapter printAdapter,
            in PrintAttributes attributes, String packageName, int appId, int userId);
    void cancelPrintJob(in PrintJobId printJobId, int appId, int userId);
    void restartPrintJob(in PrintJobId printJobId, int appId, int userId);

    void addPrintJobStateChangeListener(in IPrintJobStateChangeListener listener,
            int appId, int userId);
    void removePrintJobStateChangeListener(in IPrintJobStateChangeListener listener,
            int userId);

    /**
     * Listen for changes to the installed and enabled print services.
     *
     * @param listener the listener to add
     * @param userId the id of the user listening
     *
     * @see android.print.PrintManager#getPrintServices(int, String)
     */
    void addPrintServicesChangeListener(in IPrintServicesChangeListener listener,
            int userId);

    /**
     * Stop listening for changes to the installed and enabled print services.
     *
     * @param listener the listener to remove
     * @param userId the id of the user requesting the removal
     *
     * @see android.print.PrintManager#getPrintServices(int, String)
     */
    void removePrintServicesChangeListener(in IPrintServicesChangeListener listener,
            int userId);

    /**
     * Get the print services.
     *
     * @param selectionFlags flags selecting which services to get
     * @param userId the id of the user requesting the services
     *
     * @return the list of selected print services.
     */
    List<PrintServiceInfo> getPrintServices(int selectionFlags, int userId);

    /**
     * Enable or disable a print service.
     *
     * @param service The service to enabled or disable
     * @param isEnabled whether the service should be enabled or disabled
     * @param userId the id of the user requesting the services
     */
    void setPrintServiceEnabled(in ComponentName service, boolean isEnabled, int userId);

    /**
     * Listen for changes to the print service recommendations.
     *
     * @param listener the listener to add
     * @param userId the id of the user listening
     *
     * @see android.print.PrintManager#getPrintServiceRecommendations
     */
    void addPrintServiceRecommendationsChangeListener(in IRecommendationsChangeListener listener,
            int userId);

    /**
     * Stop listening for changes to the print service recommendations.
     *
     * @param listener the listener to remove
     * @param userId the id of the user requesting the removal
     *
     * @see android.print.PrintManager#getPrintServiceRecommendations
     */
    void removePrintServiceRecommendationsChangeListener(in IRecommendationsChangeListener listener,
            int userId);

    /**
     * Get the print service recommendations.
     *
     * @param userId the id of the user requesting the recommendations
     *
     * @return the list of selected print services.
     */
    List<RecommendationInfo> getPrintServiceRecommendations(int userId);

    void createPrinterDiscoverySession(in IPrinterDiscoveryObserver observer, int userId);
    void startPrinterDiscovery(in IPrinterDiscoveryObserver observer,
            in List<PrinterId> priorityList, int userId);
    void stopPrinterDiscovery(in IPrinterDiscoveryObserver observer, int userId);
    void validatePrinters(in List<PrinterId> printerIds, int userId);
    void startPrinterStateTracking(in PrinterId printerId, int userId);

    /**
     * Get the custom icon for a printer. If the icon is not cached, the icon is
     * requested asynchronously. Once it is available the printer is updated.
     *
     * @param printerId the id of the printer the icon should be loaded for
     * @param userId the id of the user requesting the printer
     * @return the custom icon to be used for the printer or null if the icon is
     *         not yet available
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    Icon getCustomPrinterIcon(in PrinterId printerId, int userId);

    void stopPrinterStateTracking(in PrinterId printerId, int userId);
    void destroyPrinterDiscoverySession(in IPrinterDiscoveryObserver observer,
            int userId);

    /**
     * Check if the system will bind to print services in intant app.
     *
     * @param userId the Id of the user the behavior should be checked for
     *
     * @return {@code true} iff the system will bind to print services in instant apps.
     */
    boolean getBindInstantServiceAllowed(int userId);

    /**
     * Set if the system will bind to print services in intant app.
     *
     * @param userId the Id of the user the behavior should be changed for
     * @param allows iff {@code true} the system will bind to print services in instant apps
     */
    void setBindInstantServiceAllowed(int userId, boolean allowed);
}
