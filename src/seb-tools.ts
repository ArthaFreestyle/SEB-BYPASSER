import { DOMParser } from '@xmldom/xmldom';
import CryptoES from 'crypto-es';

export interface XMLDictionary {
    [key: string]: XMLValue;
}

export type XMLValue = string | number | boolean | XMLValue[] | XMLDictionary | undefined;

const ELEMENT_NODE = 1;

export class SebFile {
    public readonly Dictionnary: XMLDictionary;
    public readonly SerializedJson: string;
    public readonly StartUrl: string | undefined;
    public readonly ConfigHash: string;

    private constructor(dictionnary: XMLDictionary, serializedJson: string, startUrl: string | undefined, configKey: string) {
        this.Dictionnary = dictionnary;
        this.SerializedJson = serializedJson;
        this.StartUrl = startUrl;
        this.ConfigHash = configKey;
    }

    public static async createInstance(sebXML: string): Promise<SebFile | undefined> {
        const dictionnary = parseXMLString(sebXML);
        if (!dictionnary) {
            return;
        }
        const serializedJson = serialize(dictionnary);
        const startUrl = getStartUrl(dictionnary);
        const configHash = sha256(serializedJson);
        return new SebFile(dictionnary, serializedJson, startUrl, configHash);
    }

    public getConfigKey(url: string) {
        return sha256(url.split('#')[0] + this.ConfigHash);
    }
}

function getStartUrl(dictionnary: XMLDictionary): string | undefined {
    const startUrl = dictionnary["startURL"];
    if (typeof startUrl === "string") {
        return startUrl;
    }
}

function parseXMLString(xmlString: string): XMLDictionary | undefined {
    function processValue(node: Node): XMLValue {
        switch (node.nodeName) {
            case 'true':
                return true;
            case 'false':
                return false;
            case 'integer':
                return parseInt(node.textContent || '0', 10);
            case 'real':
                return parseFloat(node.textContent || '0');
            case 'string':
                return node.textContent || '';
            case 'data':
                return (node.textContent || '').replace(/\s+/g, '');
            case 'date':
                return node.textContent || '';
            case 'array':
                return handleArray(node);
            case 'dict':
                return handleDict(node);
            default:
                return undefined;
        }
    }

    function handleArray(node: Node): XMLValue[] {
        const array: XMLValue[] = [];
        Array.from(node.childNodes).forEach(childNode => {
            if (childNode.nodeType === ELEMENT_NODE) {
                const value = processValue(childNode);
                if (value !== undefined) {
                    array.push(value);
                }
            }
        });
        return array;
    }

    function handleDict(node: Node): XMLDictionary {
        const dict: XMLDictionary = {};
        let key: string | null = null;

        Array.from(node.childNodes).forEach(childNode => {
            if (childNode.nodeType !== ELEMENT_NODE) {
                return;
            }
            if (childNode.nodeName === 'key') {
                key = childNode.textContent || null;
            } else if (key !== null) {
                const value = processValue(childNode);
                if (value !== undefined) {
                    dict[key] = value;
                }
                key = null;
            }
        });
        return dict;
    }

    try {
        const doc = new DOMParser().parseFromString(xmlString, 'text/xml');
        const rootDict = doc.getElementsByTagName('dict').item(0);
        if (!rootDict) {
            return;
        }
        return handleDict(rootDict);
    }
    catch (e) {
        console.error('Error parsing XML:', e);
        return;
    }
}

function serialize(dictionary: XMLDictionary): string {
    function serializeValue(value: XMLValue): string {
        if (Array.isArray(value)) {
            return '[' + value.map(serializeValue).join(',') + ']';
        }
        if (value !== null && typeof value === 'object') {
            return serialize(value);
        }
        if (typeof value === 'boolean' || typeof value === 'number') {
            return String(value);
        }
        if (typeof value === 'string') {
            return JSON.stringify(value);
        }
        return '""';
    }

    const entries: string[] = [];
    const keys = Object.keys(dictionary).sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' }));

    for (const key of keys) {
        const value = dictionary[key];
        if (key.toLowerCase() === 'originatorversion') {
            continue;
        }
        if (value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0) {
            continue;
        }
        entries.push(`${JSON.stringify(key)}:${serializeValue(value)}`);
    }

    return `{${entries.join(',')}}`;
}

function sha256(str: string) {
    return CryptoES.SHA256(str).toString(CryptoES.enc.Hex);
}
