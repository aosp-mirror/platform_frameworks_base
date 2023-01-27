// Copyright 2022 Google LLC

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.import { exec } from 'child_process';

import { FileIO, TCSVRecord } from './FileIO';
import ProcessArgs from './processArgs';

interface IInputMigItem {
    migrationToken: string;
    materialToken: string;
    newDefaultValue?: string;
    newComment?: string;
}

interface IAditionalKeys {
    step: ('update' | 'duplicate' | 'add' | 'ignore')[];
    isHidden: boolean;
    replaceToken: string;
}

export type IMigItem = Omit<IInputMigItem, 'materialToken' | 'migrationToken'> & IAditionalKeys;

export type IMigrationMap = Map<string, IMigItem>;

function isMigrationRecord(record: TCSVRecord): record is string[] {
    return !record.some((value) => typeof value != 'string') || record.length != 5;
}

export const loadMIgrationList = async function (): Promise<IMigrationMap> {
    const out: IMigrationMap = new Map();
    const csv = await FileIO.loadCSV('resources/migrationList.csv');

    csv.forEach((record, i) => {
        if (i == 0) return; // header

        if (typeof record[0] != 'string') return;

        if (!isMigrationRecord(record)) {
            console.log(`Failed to validade CSV record as string[5].`, record);
            process.exit();
        }

        const [originalToken, materialToken, newDefaultValue, newComment, migrationToken] = record;

        if (out.has(originalToken)) {
            console.log('Duplicated entry on Migration CSV file: ', originalToken);
            return;
        }

        out.set(originalToken, {
            replaceToken: ProcessArgs.isDebug ? migrationToken : materialToken,
            ...(!!newDefaultValue && { newDefaultValue }),
            ...(!!newComment && { newComment }),
            step: [],
            isHidden: false,
        });
    });

    return new Map([...out].sort((a, b) => b[0].length - a[0].length));
};
