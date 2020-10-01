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

DROP TABLE IF EXISTS tracing_mark_write_split;

CREATE TABLE tracing_mark_write_split (
    raw_ftrace_entry_id INT REFERENCES raw_ftrace_entries (id),
    atrace_type CHAR(1), -- only null for the first 2 sync timers. usually 'B', 'C', E', ...
    atrace_pid INT,      -- only null for first 2 sync timers
    atrace_message,      -- usually null for type='E' etc.
    atrace_count,        -- usually non-null only for 'C'

    UNIQUE(raw_ftrace_entry_id) -- drops redundant inserts into table
);

INSERT INTO tracing_mark_write_split
WITH
    pivoted AS (
        SELECT tx.predictorset_id,
               --ty.predictorset_id,
               --tz.predictorset_id,
               --tzz.predictorset_id,
               tx.predictor_name AS atrace_type,
               CAST(ty.predictor_name  AS integer) AS atrace_pid,
               tz.predictor_name AS atrace_message,
               CAST(tzz.predictor_name AS integer) AS atrace_count
        FROM (SELECT * from tracing_mark_write_split_array WHERE gen = 1) AS tx
        LEFT JOIN
             (SELECT * FROM tracing_mark_write_split_array WHERE gen = 2) AS ty
        ON tx.predictorset_id = ty.predictorset_id
        LEFT JOIN
             (SELECT * FROM tracing_mark_write_split_array WHERE gen = 3) AS tz
        ON tx.predictorset_id = tz.predictorset_id
        LEFT JOIN
             (SELECT * FROM tracing_mark_write_split_array WHERE gen = 4) AS tzz
        ON tx.predictorset_id = tzz.predictorset_id
    )
SELECT * from pivoted ORDER BY predictorset_id;-- LIMIT 100;

SELECT * FROM tracing_mark_write_split;
