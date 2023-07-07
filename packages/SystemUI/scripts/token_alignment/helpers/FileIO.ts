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

import { exec } from 'child_process';
import { parse } from 'csv-parse';
import { promises as fs } from 'fs';
import jsdom from 'jsdom';

const DOMParser = new jsdom.JSDOM('').window.DOMParser as typeof window.DOMParser;

type TFileList = string[];

export type TCSVRecord = Array<string | boolean | number>;

class _FileIO {
    public parser = new DOMParser();
    public saved: string[] = [];

    public loadXML = async (path: string): Promise<XMLDocument> => {
        try {
            const src = await this.loadFileAsText(path);
            return this.parser.parseFromString(src, 'text/xml') as XMLDocument;
        } catch (error) {
            console.log(`Failed to parse XML file '${path}'.`, error);
            process.exit();
        }
    };

    public loadFileAsText = async (path: string): Promise<string> => {
        try {
            return await fs.readFile(path, { encoding: 'utf8' });
        } catch (error) {
            console.log(`Failed to read file '${path}'.`, error);
            process.exit();
        }
    };

    public saveFile = async (data: string, path: string) => {
        try {
            await fs.writeFile(path, data, { encoding: 'utf8' });
            this.saved.push(path);
        } catch (error) {
            console.log(error);
            console.log(`Failed to write file '${path}'.`);
            process.exit();
        }
    };

    public loadFileList = async (path: string): Promise<TFileList> => {
        const src = await this.loadFileAsText(path);

        try {
            return JSON.parse(src) as TFileList;
        } catch (error) {
            console.log(error);
            console.log(`Failed to parse JSON file '${path}'.`);
            process.exit();
        }
    };

    public loadCSV = (path: string): Promise<Array<TCSVRecord>> => {
        return new Promise((resolve, reject) => {
            this.loadFileAsText(path).then((src) => {
                parse(
                    src,
                    {
                        delimiter: '	',
                    },
                    (err, records) => {
                        if (err) {
                            reject(err);
                            return;
                        }

                        resolve(records);
                    }
                );
            });
        });
    };

    formatSaved = () => {
        const cmd = `idea format ${this.saved.join(' ')}`;

        exec(cmd, (error, out, stderr) => {
            if (error) {
                console.log(error.message);
                return;
            }

            if (stderr) {
                console.log(stderr);
                return;
            }

            console.log(out);
        });
    };
}

export const FileIO = new _FileIO();
