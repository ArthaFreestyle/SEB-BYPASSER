import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { SebFile } from '../src/seb-tools.ts';

type HeaderEntry = { name: string; value?: string };
type BeforeSendCb = (details: { url: string; requestHeaders?: HeaderEntry[] }) => { requestHeaders: HeaderEntry[] } | void;

let registeredListener: BeforeSendCb | undefined;
let registeredUrls: string[] = [];
const postedMessages: unknown[] = [];
let messageHandler: ((msg: unknown) => void) | undefined;

(globalThis as Record<string, unknown>).browser = {
    runtime: {
        connectNative: () => ({
            onMessage: { addListener: (cb: (msg: unknown) => void) => { messageHandler = cb; } },
            onDisconnect: { addListener: () => {} },
            postMessage: (msg: unknown) => { postedMessages.push(msg); }
        })
    },
    webRequest: {
        onBeforeSendHeaders: {
            addListener: (cb: BeforeSendCb, filter: { urls: string[] }) => {
                registeredListener = cb;
                registeredUrls = filter.urls;
            },
            removeListener: (cb: BeforeSendCb) => {
                if (registeredListener === cb) {
                    registeredListener = undefined;
                }
            }
        }
    }
};

await import('../src/android-ext/background.ts');
assert.ok(messageHandler, 'native port message handler must register');
assert.equal((postedMessages[0] as { enabled: boolean }).enabled, false);

const xml = `<plist><dict><key>startURL</key><string>https://exam.example.com/quiz</string></dict></plist>`;
const seb = await SebFile.createInstance(xml);
assert.ok(seb);

messageHandler({ action: 'enable', sebXML: xml, rqUrlsFilter: ['https://exam.example.com/*'] });
await new Promise(resolve => setTimeout(resolve, 20));

const enabledStatus = postedMessages[postedMessages.length - 1] as {
    enabled: boolean;
    sebStartUrl: string;
    configHash: string;
};
assert.equal(enabledStatus.enabled, true);
assert.equal(enabledStatus.sebStartUrl, 'https://exam.example.com/quiz');
assert.equal(enabledStatus.configHash, seb.ConfigHash);
assert.deepEqual(registeredUrls, ['https://exam.example.com/*']);
assert.ok(registeredListener, 'webRequest listener should be active');

const rewritten = registeredListener({
    url: 'https://exam.example.com/quiz?q=1',
    requestHeaders: [
        { name: 'User-Agent', value: 'Mozilla/5.0 Android' },
        { name: 'sec-ch-ua-platform', value: '"Android"' },
        { name: 'X-SafeExamBrowser-ConfigKeyHash', value: 'stale' }
    ]
});
assert.ok(rewritten);
assert.ok(rewritten.requestHeaders.some(h => h.name === 'User-Agent' && h.value?.includes('SEB/3.7.0')));
assert.ok(rewritten.requestHeaders.some(h => h.name === 'sec-ch-ua-platform' && h.value === '"Windows"'));
const ckh = rewritten.requestHeaders.filter(h => h.name === 'X-SafeExamBrowser-ConfigKeyHash');
assert.equal(ckh.length, 1);
assert.equal(ckh[0].value, seb.getConfigKey('https://exam.example.com/quiz?q=1'));

messageHandler({ action: 'disable' });
await new Promise(resolve => setTimeout(resolve, 10));
assert.equal(registeredListener, undefined);
assert.equal((postedMessages[postedMessages.length - 1] as { enabled: boolean }).enabled, false);

const manifestPath = path.resolve(import.meta.dirname, '../android/app/src/main/assets/seb-extension/manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) as {
    manifest_version?: number;
    browser_specific_settings?: { gecko?: { id?: string } };
    permissions?: string[];
    background?: { scripts?: string[] };
};
assert.equal(manifest.manifest_version, 2, 'GeckoView built-in extension must stay MV2');
assert.equal(manifest.browser_specific_settings?.gecko?.id, 'seb-bypass@overlord08x.dev');
assert.ok(manifest.permissions?.includes('geckoViewAddons'));
assert.ok(manifest.permissions?.includes('nativeMessaging'));
assert.ok(manifest.permissions?.includes('webRequest'));
assert.ok(manifest.permissions?.includes('webRequestBlocking'));
assert.ok(manifest.background?.scripts?.includes('background.js'));

console.log('android-ext checks passed');
