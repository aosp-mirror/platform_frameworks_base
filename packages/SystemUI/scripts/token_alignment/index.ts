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

import DOM from './helpers/DOMFuncs';
import { FileIO } from './helpers/FileIO';
import { loadMIgrationList } from './helpers/migrationList';
import { processQueriedEntries, TEvalExistingEntry } from './helpers/processXML';
import { repoPath } from './helpers/rootPath';
import { groupReplace } from './helpers/textFuncs';

async function init() {
    const migrationMap = await loadMIgrationList();
    const basePath = `${repoPath}/../tm-qpr-dev/frameworks/base/core/res/res/values/`;

    await processQueriedEntries(migrationMap, {
        containerQuery: 'declare-styleable[name="Theme"]',
        hidable: true,
        path: `${basePath}attrs.xml`,
        step: 0,
        tagName: 'attr',
        evalExistingEntry: (_attrValue, migItem, qItem) => {
            const { hidden, textContent: currentComment } = DOM.getElementComment(qItem);

            if (hidden) migItem.isHidden = hidden;

            const { newComment } = migItem;
            return [
                hidden ? 'update' : 'duplicate',
                {
                    attrs: { name: migItem.replaceToken },
                    ...(newComment
                        ? { comment: `${newComment} @hide ` }
                        : currentComment
                        ? { comment: hidden ? currentComment : `${currentComment} @hide ` }
                        : {}),
                },
            ];
        },
        evalMissingEntry: (_originalToken, { replaceToken, newComment }) => {
            return {
                tagName: 'attr',
                attrs: {
                    name: replaceToken,
                    format: 'color',
                },
                comment: `${newComment} @hide `,
            };
        },
    });

    // only update all existing entries
    await processQueriedEntries(migrationMap, {
        tagName: 'item',
        path: `${basePath}themes_device_defaults.xml`,
        containerQuery: 'resources',
        step: 2,
        evalExistingEntry: (_attrValue, { isHidden, replaceToken, step }, _qItem) => {
            if (step[0] != 'ignore')
                return [
                    isHidden ? 'update' : 'duplicate',
                    {
                        attrs: { name: replaceToken },
                    },
                ];
        },
    });

    // add missing entries on specific container
    await processQueriedEntries(migrationMap, {
        tagName: 'item',
        path: `${basePath}themes_device_defaults.xml`,
        containerQuery: 'resources style[parent="Theme.Material"]',
        step: 3,
        evalMissingEntry: (originalToken, { newDefaultValue, replaceToken }) => {
            return {
                tagName: 'item',
                content: newDefaultValue,
                attrs: {
                    name: replaceToken,
                },
            };
        },
    });

    const evalExistingEntry: TEvalExistingEntry = (_attrValue, { replaceToken, step }, _qItem) => {
        if (step[0] == 'update')
            return [
                'update',
                {
                    attrs: { name: replaceToken },
                },
            ];
    };

    await processQueriedEntries(migrationMap, {
        tagName: 'item',
        containerQuery: 'resources',
        path: `${basePath}../values-night/themes_device_defaults.xml`,
        step: 4,
        evalExistingEntry,
    });

    await processQueriedEntries(migrationMap, {
        tagName: 'java-symbol',
        path: `${basePath}symbols.xml`,
        containerQuery: 'resources',
        step: 5,
        evalExistingEntry,
    });

    // update attributes on tracked XML files
    {
        const searchAttrs = [
            'android:color',
            'android:indeterminateTint',
            'app:tint',
            'app:backgroundTint',
            'android:background',
            'android:tint',
            'android:drawableTint',
            'android:textColor',
            'android:fillColor',
            'android:startColor',
            'android:endColor',
            'name',
            'ns1:color',
        ];

        const filtered = new Map(
            [...migrationMap]
                .filter(([_originalToken, { step }]) => step[0] == 'update')
                .map(([originalToken, { replaceToken }]) => [originalToken, replaceToken])
        );

        const query =
            searchAttrs.map((str) => `*[${str}]`).join(',') +
            [...filtered.keys()].map((originalToken) => `item[name*="${originalToken}"]`).join(',');

        const trackedFiles = await FileIO.loadFileList(
            `${__dirname}/resources/whitelist/xmls1.json`
        );

        const promises = trackedFiles.map(async (locaFilePath) => {
            const filePath = `${repoPath}/${locaFilePath}`;

            const doc = await FileIO.loadXML(filePath);
            const docUpdated = DOM.replaceStringInAttributeValueOnQueried(
                doc.documentElement,
                query,
                searchAttrs,
                filtered
            );
            if (docUpdated) {
                await FileIO.saveFile(DOM.XMLDocToString(doc), filePath);
            } else {
                console.warn(`Failed to update tracked file: '${locaFilePath}'`);
            }
        });
        await Promise.all(promises);
    }

    // updates tag content on tracked files
    {
        const searchPrefixes = ['?android:attr/', '?androidprv:attr/'];
        const filtered = searchPrefixes
            .reduce<Array<[string, string]>>((acc, prefix) => {
                return [
                    ...acc,
                    ...[...migrationMap.entries()]
                        .filter(([_originalToken, { step }]) => step[0] == 'update')
                        .map(
                            ([originalToken, { replaceToken }]) =>
                                [`${prefix}${originalToken}`, `${prefix}${replaceToken}`] as [
                                    string,
                                    string
                                ]
                        ),
                ];
            }, [])
            .sort((a, b) => b[0].length - a[0].length);

        const trackedFiles = await FileIO.loadFileList(
            `${__dirname}/resources/whitelist/xmls2.json`
        );

        const promises = trackedFiles.map(async (locaFilePath) => {
            const filePath = `${repoPath}/${locaFilePath}`;
            const doc = await FileIO.loadXML(filePath);
            const docUpdated = DOM.replaceContentTextOnQueried(
                doc.documentElement,
                'item, color',
                filtered
            );
            if (docUpdated) {
                await FileIO.saveFile(DOM.XMLDocToString(doc), filePath);
            } else {
                console.warn(`Failed to update tracked file: '${locaFilePath}'`);
            }
        });
        await Promise.all(promises);
    }

    // replace imports on Java / Kotlin
    {
        const replaceMap = new Map(
            [...migrationMap.entries()]
                .filter(([_originalToken, { step }]) => step[0] == 'update')
                .map(
                    ([originalToken, { replaceToken }]) =>
                        [originalToken, replaceToken] as [string, string]
                )
                .sort((a, b) => b[0].length - a[0].length)
        );

        const trackedFiles = await FileIO.loadFileList(
            `${__dirname}/resources/whitelist/java.json`
        );

        const promises = trackedFiles.map(async (locaFilePath) => {
            const filePath = `${repoPath}/${locaFilePath}`;
            const fileContent = await FileIO.loadFileAsText(filePath);
            const str = groupReplace(fileContent, replaceMap, 'R.attr.(#group#)(?![a-zA-Z])');
            await FileIO.saveFile(str, filePath);
        });
        await Promise.all(promises);
    }
}

init();
