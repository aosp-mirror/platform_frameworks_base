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

import DOM, { INewTag, IUpdateTag } from './DOMFuncs';
import { FileIO } from './FileIO';
import { IMigItem, IMigrationMap } from './migrationList';

export type TResultExistingEval = ['update' | 'duplicate', IUpdateTag] | void;
export type TResultMissingEval = INewTag | void;

interface IProcessXML {
    attr?: string;
    containerQuery?: string;
    evalExistingEntry?: TEvalExistingEntry;
    evalMissingEntry?: TEvalMissingEntry;
    hidable?: boolean;
    path: string;
    step: number;
    tagName: string;
}

export type TEvalExistingEntry = (
    attrname: string,
    migItem: IMigItem,
    qItem: Element
) => TResultExistingEval;

export type TEvalMissingEntry = (originalToken: string, migItem: IMigItem) => TResultMissingEval;

export async function processQueriedEntries(
    migrationMap: IMigrationMap,
    {
        attr = 'name',
        containerQuery = '*',
        evalExistingEntry,
        path,
        step,
        tagName,
        evalMissingEntry,
    }: IProcessXML
) {
    const doc = await FileIO.loadXML(path);

    const containerElement =
        (containerQuery && doc.querySelector(containerQuery)) || doc.documentElement;

    migrationMap.forEach((migItem, originalToken) => {
        migItem.step[step] = 'ignore';

        const queryTiems = containerElement.querySelectorAll(
            `${tagName}[${attr}="${originalToken}"]`
        );

        if (evalMissingEntry) {
            const addinOptions = evalMissingEntry(originalToken, migItem);

            if (queryTiems.length == 0 && containerElement && addinOptions) {
                DOM.addEntry(containerElement, addinOptions);
                migItem.step[step] = 'add';
                return;
            }
        }

        if (evalExistingEntry)
            queryTiems.forEach((qEl) => {
                const attrName = qEl.getAttribute(attr);
                const migItem = migrationMap.get(attrName || '');

                if (!attrName || !migItem) return;

                const updateOptions = evalExistingEntry(attrName, migItem, qEl);

                if (!updateOptions) return;

                const [processType, processOptions] = updateOptions;

                switch (processType) {
                    case 'update':
                        if (DOM.updateElement(qEl, processOptions)) migItem.step[step] = 'update';
                        break;

                    case 'duplicate':
                        if (DOM.duplicateEntryWithChange(qEl, processOptions))
                            migItem.step[step] = 'duplicate';
                        break;
                }
            });
    });

    await FileIO.saveFile(doc.documentElement.outerHTML, path);
}
