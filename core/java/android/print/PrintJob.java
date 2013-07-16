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


/**
 * This class represents a print job from the perspective of
 * an application.
 */
public final class PrintJob {

    private final int mId;

    private final PrintManager mPrintManager;

    private PrintJobInfo mCachedInfo;

    PrintJob(PrintJobInfo info, PrintManager printManager) {
        mCachedInfo = info;
        mPrintManager = printManager;
        mId = info.getId();
    }

    /**
     * Gets the unique print job id.
     *
     * @return The id.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the {@link PrintJobInfo} that describes this job.
     * <p>
     * <strong>Node:</strong>The returned info object is a snapshot of the
     * current print job state. Every call to this method returns a fresh
     * info object that reflects the current print job state.
     * </p>
     *
     * @return The print job info.
     */
    public PrintJobInfo getInfo() {
        PrintJobInfo info = mPrintManager.getPrintJobInfo(mId);
        if (info != null) {
            mCachedInfo = info;
        }
        return mCachedInfo;
    }

    /**
     * Cancels this print job.
     */
    public void cancel() {
        mPrintManager.cancelPrintJob(mId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PrintJob other = (PrintJob) obj;
        return mId == other.mId;
    }

    @Override
    public int hashCode() {
        return mId;
    }
}
