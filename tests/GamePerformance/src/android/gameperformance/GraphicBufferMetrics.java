/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.gameperformance;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Utility class that performs analysis of atrace logs. This is implemented without Android
 * dependencies and therefore can be used in stand-alone mode.
 * Idea of this is to track atrace gfx event from graphics buffer producer/consumer.
 * We analyze here from surfaceflinger
 *   queueBuffer - event when buffer was queued.
 *   acquireBuffer - event when buffer was requested for composition.
 *   releaseBuffer - even when buffer was released after composition.
 * This also track events, issued locally
 *   localPostBuffer - event when buffer was posted from the local app.
 *
 *  queueBuffer, acquireBuffer, releaseBuffer is accompanied with buffer name so we
 *  can track life-cycle of particular buffer.
 *  We don't have such information for localPostBuffer, however we can track next queueBuffer
 *  from surfaceflinger corresponds to previous localPostBuffer.
 *
 *  Following results are calculated:
 *    post_time_[min/max/avr]_mcs - time for localPostBuffer duration.
 *    ready_time_[min/max/avr]_mcs - time from localPostBuffer to when buffer was acquired by
 *                                   surfaceflinger.
 *    latency_[min/max/avr]_mcs - time from localPostBuffer to when buffer was released by
 *                                 surfaceflinger.
 *    missed_frame_percents - percentage of missed frames (frames that do not have right sequence
 *                            of events).
 *
 * Following is example of atrace logs from different platforms
 *            <...>-5237  (-----) [000] ...1   228.380392: tracing_mark_write: B|11|SurfaceView - android.gameperformance/android.gameperformance.GamePerformanceActivity#0: 2
 *   surfaceflinger-5855  ( 5855) [001] ...1   169.627364: tracing_mark_write: B|24|acquireBuffer
 *   HwBinder:617_2-652   (  617) [002] d..1 360262.921756: sde_evtlog: 617|sde_encoder_virt_atomic_check:855|19|0|0|0|0|0|0|0|0|0|0|0|0|0|0
 */
public class GraphicBufferMetrics {
    private final static String TAG = "GraphicBufferMetrics";

    private final static String KEY_POST_TIME = "post_time";
    private final static String KEY_READY_TIME = "ready_time";
    private final static String KEY_LATENCY = "latency";
    private final static String SUFFIX_MIN = "min";
    private final static String SUFFIX_MAX = "max";
    private final static String SUFFIX_MEDIAN = "median";
    private final static String KEY_MISSED_FRAME_RATE = "missed_frame_percents";

    private final static int EVENT_POST_BUFFER = 0;
    private final static int EVENT_QUEUE_BUFFER = 1;
    private final static int EVENT_ACQUIRE_BUFFER = 2;
    private final static int EVENT_RELEASE_BUFFER = 3;

    // atrace prints this line. Used as a marker to make sure that we can parse its output.
    private final static String ATRACE_HEADER =
            "#           TASK-PID    TGID   CPU#  ||||    TIMESTAMP  FUNCTION";

    private final static String[] KNOWN_PHRASES = new String[] {
            "capturing trace... done", "TRACE:"};
    private final static List<String> KNWON_PHRASES_LIST = Arrays.asList(KNOWN_PHRASES);

    // Type of the atrace event we can parse and analyze.
    private final static String FUNCTION_TRACING_MARK_WRITE = "tracing_mark_write";

    // Trace event we can ignore. It contains current timestamp information for the atrace output.
    private final static String TRACE_EVENT_CLOCK_SYNC = "trace_event_clock_sync:";

    // Threshold we consider test passes successfully. If we cannot collect enough amount of frames
    // let fail the test. 50 is calculated 10 frames per second running for five seconds.
    private final static int MINIMAL_SAMPLE_CNT_TO_PASS = 50;

    /**
     * Raw event in atrace. Stored hierarchically.
     */
    private static class RawEvent {
        // Parent of this event or null for the root holder.
        public final RawEvent mParent;
        // Time of the event in mcs.
        public final long mTime;
        // Duration of the event in mcs.
        public long mDuration;
        // Name/body of the event.
        public final String mName;
        // Children events.
        public final List<RawEvent> mChildren;

        public RawEvent(RawEvent parent, long time, String name) {
            mParent = parent;
            mTime = time;
            mName = name;
            mDuration = -1;
            mChildren = new ArrayList<>();
        }

        /**
         * Recursively finds child events.
         * @param path specify path to events. For example a/b. That means to find child with name
         *             'a' of the current event and in this child find child with name 'b'. Path
         *             can consist from only one segment and that means we analyze only children of
         *             the current event.
         * @param collector to collect found events.
         */
        public void findEvents(String path, List<RawEvent> collector) {
            final int separator = path.indexOf('/');
            final String current = separator > 0 ? path.substring(0, separator) : path;
            final String nextPath = separator > 0 ? path.substring(separator + 1) : null;
            for (RawEvent child : mChildren) {
                if (child.mName.equals(current)) {
                    if (nextPath != null) {
                        child.findEvents(nextPath, collector);
                    } else {
                        collector.add(child);
                    }
                }
            }
        }

        public void dump(String prefix) {
            System.err.print(prefix);
            System.err.println(mTime + "[" + mDuration + "] " + mName);
            for (RawEvent e : mChildren) {
                e.dump(prefix + "  ");
            }
        }
    }

    /**
     * Describes graphic buffer event. local post, queued, acquired, released.
     */
    private static class BufferEvent {
        public final int mType;
        public final long mTime;
        public final long mDuration;
        public final String mBufferId;

        public BufferEvent(int type, long time, long duration, String bufferId) {
            mType = type;
            mTime = time;
            mDuration = duration;
            mBufferId = bufferId;
        }

        @Override
        public String toString() {
            return "Type: " + mType + ". Time: " + mTime +
                    "[" + mDuration + "]. Buffer: " + mBufferId + ".";
        }
    }

    /**
     * Returns true if given char is digit.
     */
    private static boolean isDigitChar(char c) {
        return (c >= '0') && (c <= '9');
    }

    /**
     * Returns true if given char is digit or '.'.
     */
    private static boolean isDoubleDigitChar(char c) {
        return (c == '.') || isDigitChar(c);
    }

    /**
     * Convert timestamp string that represents double value in seconds to long time that represents
     * timestamp in microseconds.
     */
    private static long getTimeStamp(String timeStampStr) {
        return (long)(1000000.0 * Double.parseDouble(timeStampStr));
    }

    /**
     * Reads atrace log and build event model. Result is a map, where key specifies event for the
     * particular thread. Value is the synthetic root RawEvent that holds all events for the
     * thread. Events are stored hierarchically.
     */
    private static Map<Integer, RawEvent> buildEventModel(String fileName) throws IOException {
        Map<Integer, RawEvent> result = new HashMap<>();

        BufferedReader bufferedReader = null;
        String line = "";
        boolean headerDetected = false;
        try {
            bufferedReader = new BufferedReader(new FileReader(fileName));
            while ((line = bufferedReader.readLine()) != null) {
                // Make sure we find comment that describes output format we can with.
                headerDetected |= line.equals(ATRACE_HEADER);
                // Skip comments.
                if (line.startsWith("#")) {
                    continue;
                }
                // Skip known service output
                if (KNWON_PHRASES_LIST.contains(line)) {
                    continue;
                }

                if (!headerDetected) {
                    // We don't know the format of this line.
                    throw new IllegalStateException("Header was not detected");
                }

                // TASK-PID in header exists at position 12. PID position 17 should contains first
                // digit of thread id after the '-'.
                if (!isDigitChar(line.charAt(17)) || line.charAt(16) != '-') {
                    throw new IllegalStateException("Failed to parse thread id: " + line);
                }
                int rightIndex = line.indexOf(' ', 17);
                final String threadIdStr = line.substring(17, rightIndex);
                final int threadId = Integer.parseInt(threadIdStr);

                // TIMESTAMP in header exists at position 45
                // This position should point in the middle of timestamp which is ended by ':'.
                int leftIndex = 45;
                while (isDoubleDigitChar(line.charAt(leftIndex))) {
                    --leftIndex;
                }
                rightIndex = line.indexOf(':', 45);

                final String timeStampString = line.substring(leftIndex + 1, rightIndex);
                final long timeStampMcs = getTimeStamp(timeStampString);

                // Find function name, pointed by FUNCTION. Long timestamp can shift if position
                // so use end of timestamp to find the function which is ended by ':'.
                leftIndex = rightIndex + 1;
                while (Character.isWhitespace(line.charAt(leftIndex))) {
                    ++leftIndex;
                }
                rightIndex = line.indexOf(':', leftIndex);
                final String function = line.substring(leftIndex, rightIndex);

                if (!function.equals(FUNCTION_TRACING_MARK_WRITE)) {
                    continue;
                }

                // Rest of the line is event body.
                leftIndex = rightIndex + 1;
                while (Character.isWhitespace(line.charAt(leftIndex))) {
                    ++leftIndex;
                }

                final String event = line.substring(leftIndex);
                if (event.startsWith(TRACE_EVENT_CLOCK_SYNC)) {
                    continue;
                }

                // Parse event, for example:
                // B|615|SurfaceView - android.gameperformance.GamePerformanceActivity#0: 1
                // E|615
                // C|11253|operation id|2
                StringTokenizer eventTokenizer = new StringTokenizer(event, "|");
                final String eventType = eventTokenizer.nextToken();

                // Attach root on demand.
                if (!result.containsKey(threadId)) {
                    result.put(threadId, new RawEvent(null /* parent */,
                                                      timeStampMcs,
                                                      "#ROOT_" + threadId));
                }

                switch (eventType) {
                case "B": {
                        // Log entry starts.
                        eventTokenizer.nextToken(); // PID
                        String eventText = eventTokenizer.nextToken();
                        while (eventTokenizer.hasMoreTokens()) {
                            eventText += " ";
                            eventText += eventTokenizer.nextToken();
                        }
                        RawEvent parent = result.get(threadId);
                        RawEvent current = new RawEvent(parent, timeStampMcs, eventText);
                        parent.mChildren.add(current);
                        result.put(threadId, current);
                    }
                    break;
                case "E": {
                        // Log entry ends.
                        RawEvent current = result.get(threadId);
                        current.mDuration = timeStampMcs - current.mTime;
                        if (current.mParent == null) {
                            // Detect a tail of the previous call. Remove last child element if it
                            // exists once it does not belong to the root.
                            if (!current.mChildren.isEmpty()) {
                                current.mChildren.remove(current.mChildren.size() -1);
                            }
                        } else {
                            result.put(threadId, current.mParent);
                        }
                    }
                    break;
                case "C":
                    // Counter, ignore
                    break;
                default:
                    throw new IllegalStateException(
                            "Unrecognized trace: " + line + " # " + eventType + " # " + event);
                }
            }

            // Detect incomplete events and detach from the root.
            Set<Integer> threadIds = new TreeSet<>();
            threadIds.addAll(result.keySet());
            for (int threadId : threadIds) {
                RawEvent root = result.get(threadId);
                if (root.mParent == null) {
                    // Last trace was closed.
                    continue;
                }
                // Find the root.
                while (root.mParent != null) {
                    root = root.mParent;
                }
                // Discard latest incomplete element.
                root.mChildren.remove(root.mChildren.size() - 1);
                result.put(threadId, root);
            }
        } catch (Exception e) {
            throw new IOException("Failed to process " + line, e);
        } finally {
            Utils.closeQuietly(bufferedReader);
        }

        return result;
    }

    /**
     * Processes provided atrace log and calculates graphics buffer metrics.
     * @param fileName name of atrace log file.
     * @param testTag tag to separate results for the different passes.
     */
    public static Map<String, Double> processGraphicBufferResult(
            String fileName, String testTag) throws IOException {
        final Map<Integer, RawEvent> model = buildEventModel(fileName);

        List<RawEvent> collectorPostBuffer = new ArrayList<>();
        List<RawEvent> collectorQueueBuffer = new ArrayList<>();
        List<RawEvent> collectorReleaseBuffer = new ArrayList<>();
        List<RawEvent> collectorAcquireBuffer = new ArrayList<>();

        // Collect required events.
        for (RawEvent root : model.values()) {
            // Surface view
            root.findEvents("localPostBuffer", collectorPostBuffer);
            // OpengGL view
            root.findEvents("eglSwapBuffersWithDamageKHR", collectorPostBuffer);

            root.findEvents("queueBuffer", collectorQueueBuffer);
            root.findEvents("onMessageReceived/handleMessageInvalidate/latchBuffer/" +
                    "updateTexImage/acquireBuffer",
                    collectorAcquireBuffer);
            // PI stack
            root.findEvents(
                    "onMessageReceived/handleMessageRefresh/postComposition/releaseBuffer",
                    collectorReleaseBuffer);
            // NYC stack
            root.findEvents(
                    "onMessageReceived/handleMessageRefresh/releaseBuffer",
                    collectorReleaseBuffer);
        }

        // Convert raw event to buffer events.
        List<BufferEvent> bufferEvents = new ArrayList<>();
        for (RawEvent event : collectorPostBuffer) {
            bufferEvents.add(
                    new BufferEvent(EVENT_POST_BUFFER, event.mTime, event.mDuration, null));
        }
        toBufferEvents(EVENT_QUEUE_BUFFER, collectorQueueBuffer, bufferEvents);
        toBufferEvents(EVENT_ACQUIRE_BUFFER, collectorAcquireBuffer, bufferEvents);
        toBufferEvents(EVENT_RELEASE_BUFFER, collectorReleaseBuffer, bufferEvents);

        // Sort events based on time. These events are originally taken from different threads so
        // order is not always preserved.
        Collections.sort(bufferEvents, new Comparator<BufferEvent>() {
            @Override
            public int compare(BufferEvent o1, BufferEvent o2) {
                if (o1.mTime < o2.mTime) {
                    return -1;
                } if (o1.mTime > o2.mTime) {
                    return +1;
                } else {
                    return 0;
                }
            }
        });

        // Collect samples.
        List<Long> postTimes = new ArrayList<>();
        List<Long> readyTimes = new ArrayList<>();
        List<Long> latencyTimes = new ArrayList<>();
        long missedCnt = 0;

        for (int i = 0; i < bufferEvents.size(); ++i) {
            if (bufferEvents.get(i).mType != EVENT_POST_BUFFER) {
                continue;
            }
            final int queueIndex = findNextOfType(bufferEvents, i + 1, EVENT_QUEUE_BUFFER);
            if (queueIndex < 0) {
                break;
            }
            final int acquireIndex = findNextOfBufferId(bufferEvents, queueIndex + 1,
                    bufferEvents.get(queueIndex).mBufferId);
            if (acquireIndex < 0) {
                break;
            }
            if (bufferEvents.get(acquireIndex).mType != EVENT_ACQUIRE_BUFFER) {
                // Was not actually presented.
                ++missedCnt;
                continue;
            }
            final int releaseIndex = findNextOfBufferId(bufferEvents, acquireIndex + 1,
                    bufferEvents.get(queueIndex).mBufferId);
            if (releaseIndex < 0) {
                break;
            }
            if (bufferEvents.get(releaseIndex).mType != EVENT_RELEASE_BUFFER) {
                // Was not actually presented.
                ++missedCnt;
                continue;
            }

            postTimes.add(bufferEvents.get(i).mDuration);
            readyTimes.add(
                    bufferEvents.get(acquireIndex).mTime - bufferEvents.get(i).mTime);
            latencyTimes.add(
                    bufferEvents.get(releaseIndex).mTime - bufferEvents.get(i).mTime);
        }

        if (postTimes.size() < MINIMAL_SAMPLE_CNT_TO_PASS) {
            throw new IllegalStateException("Too few sample cnt: " + postTimes.size() +". " +
                    MINIMAL_SAMPLE_CNT_TO_PASS + " is required.");
        }

        Map<String, Double> status = new TreeMap<>();
        addResults(status, testTag, KEY_POST_TIME, postTimes);
        addResults(status, testTag, KEY_READY_TIME, readyTimes);
        addResults(status, testTag, KEY_LATENCY, latencyTimes);
        status.put(testTag + "_" + KEY_MISSED_FRAME_RATE,
                100.0 * missedCnt / (missedCnt + postTimes.size()));
        return status;
    }

    private static void addResults(
            Map<String, Double> status, String tag, String key, List<Long> times) {
        Collections.sort(times);
        long min = times.get(0);
        long max = times.get(0);
        for (long time : times) {
            min = Math.min(min, time);
            max = Math.max(max, time);
        }
        status.put(tag + "_" + key + "_" + SUFFIX_MIN, (double)min);
        status.put(tag + "_" + key + "_" + SUFFIX_MAX, (double)max);
        status.put(tag + "_" + key + "_" + SUFFIX_MEDIAN, (double)times.get(times.size() / 2));
    }

    // Helper to convert surface flinger events to buffer events.
    private static void toBufferEvents(
            int type, List<RawEvent> rawEvents, List<BufferEvent> bufferEvents) {
        for (RawEvent event : rawEvents) {
            if (event.mChildren.isEmpty()) {
                throw new IllegalStateException("Buffer name is expected");
            }
            final String bufferName = event.mChildren.get(0).mName;
            if (bufferName.startsWith("SurfaceView - android.gameperformance")) {
                bufferEvents.add(
                        new BufferEvent(type, event.mTime, event.mDuration, bufferName));
            }
        }
    }

    private static int findNextOfType(List<BufferEvent> events, int startIndex, int type) {
        for (int i = startIndex; i < events.size(); ++i) {
            if (events.get(i).mType == type) {
                return i;
            }
        }
        return -1;
    }

    private static int findNextOfBufferId(
            List<BufferEvent> events, int startIndex, String bufferId) {
        for (int i = startIndex; i < events.size(); ++i) {
            if (bufferId.equals(events.get(i).mBufferId)) {
                return i;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: " + TAG + " atrace.log");
            return;
        }

        try {
            System.out.println("Results:");
            for (Map.Entry<?, ?> entry :
                processGraphicBufferResult(args[0], "default").entrySet()) {
                System.out.println("    " + entry.getKey() + " = " + entry.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
