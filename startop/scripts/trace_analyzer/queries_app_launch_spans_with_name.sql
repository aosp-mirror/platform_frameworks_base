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

-- use the 'launching: $process_name' async slice to figure out launch duration.
DROP VIEW IF EXISTS launch_durations_named;

CREATE VIEW launch_durations_named AS
WITH
    launch_traces_raw AS (
        SELECT *
        FROM tracing_mark_write_split AS tmw,
             raw_ftrace_entries AS rfe
        WHERE atrace_message LIKE 'launching: %' AND rfe.id = tmw.raw_ftrace_entry_id
    ),
    launch_traces_joined AS (
        SELECT started.timestamp AS started_timestamp,
               finished.timestamp AS finished_timestamp,
               started.id AS started_id,
               finished.id AS finished_id,
               SUBSTR(started.atrace_message, 12) AS proc_name   -- crop out "launching: " from the string.
        FROM launch_traces_raw AS started,
             launch_traces_raw AS finished
        -- async slices ('S' -> 'F') have matching counters given the same PID.
        WHERE started.atrace_type == 'S'
              AND finished.atrace_type == 'F'
              AND started.atrace_count == finished.atrace_count
              AND started.atrace_pid == finished.atrace_pid
    )
SELECT * from launch_traces_joined;

SELECT * FROM launch_durations_named;
