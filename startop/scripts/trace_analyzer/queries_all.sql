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

-- filter for atrace writes
CREATE VIEW IF NOT EXISTS tracing_mark_writes AS
    SELECT *
      FROM raw_ftrace_entries
     WHERE function = 'tracing_mark_write';

-- split the tracing_mark_write function args by ||s
DROP TABLE IF exists tracing_mark_write_split_array;

CREATE TABLE tracing_mark_write_split_array (
    predictorset_id INT REFERENCES raw_ftrace_entries (id),
    predictor_name,
    rest,
    gen,
    
    UNIQUE(predictorset_id, gen) -- drops redundant inserts into table
);

CREATE INDEX "tracing_mark_write_split_array_id" ON tracing_mark_write_split_array (
    predictorset_id COLLATE BINARY COLLATE BINARY
);

INSERT INTO tracing_mark_write_split_array
  WITH 
    split(predictorset_id, predictor_name, rest, gen) AS (
      -- split by |
      SELECT id, '', function_args || '|', 0 FROM tracing_mark_writes WHERE id
       UNION ALL
      SELECT predictorset_id, 
             substr(rest, 0, instr(rest, '|')),
             substr(rest, instr(rest, '|')+1),
             gen + 1
        FROM split
       WHERE rest <> ''),
     split_results AS (
       SELECT * FROM split WHERE predictor_name <> ''
     )
  SELECT * from split_results
;


