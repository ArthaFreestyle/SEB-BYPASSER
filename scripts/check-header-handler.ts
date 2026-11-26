import assert from 'node:assert/strict';
import { SebFile } from '../src/seb-tools.ts';

type EventListener = (source: { tabId?: number }, method: string, params: unknown) => void;
type DetachListener = (source: { tabId?: number }) => void;

const eventListeners: EventListener[] = [];
const detachListeners: DetachListener[] = [];
const sentCommands: Array<{ tabId: number; method: string; params: unknown }> = [];
let attachCalls = 0;
let nextSendError: Error | undefined;
let failAttach = false;

(globalThis as Record<string, unknown>).chrome = {
    debugger: {
        onEvent: { addListener: (fn: EventListener) => eventListeners.push(fn) },
        onDetach: { addListener: (fn: DetachListener) => detachListeners.push(fn) },
        attach: async () => {
            attachCalls++;
            if (failAttach) {
                throw new Error('Another debugger is already attached');
            }
            await new Promise(resolve => setTimeout(resolve, 10));
        },
        detach: async () => {},
        sendCommand: async (target: { tabId: number }, method: string, params?: unknown) => {
            sentCommands.push({ tabId: target.tabId, method, params });
            if (nextSendError) {
                const err = nextSendError;
                nextSendError = undefined;
                throw err;
            }
        }
    },
    tabs: {
        get: async (tabId: number) => ({ id: tabId, url: 'https://exam.example.com/quiz' }),
        query: async () => []
    },
    runtime: {
        getPlatformInfo: async () => ({})
    }
};

const { HeaderHandler } = await import('../src/header-handler.ts');

const xml = `<plist><dict><key>startURL</key><string>https://exam.example.com/quiz</string></dict></plist>`;
const seb = await SebFile.createInstance(xml);
assert.ok(seb);

const handler = new HeaderHandler();

// 1. Concurrent ensureTab calls for the same tab coalesce to a single attach + Fetch.enable
let resolveRestore!: () => void;
handler.setRestorePromise(new Promise<void>(resolve => { resolveRestore = resolve; }));
const p1 = handler.ensureTab(101);
const p2 = handler.ensureTab(101);
handler.enable(seb, ['<all_urls>']);
resolveRestore();
await Promise.all([p1, p2]);
assert.equal(attachCalls, 1);
assert.equal(sentCommands.filter(c => c.tabId === 101 && c.method === 'Fetch.enable').length, 1);

// 2. onRequestPaused injects User-Agent, Windows platform client hint, and ConfigKeyHash
sentCommands.length = 0;
for (const fn of eventListeners) {
    fn({ tabId: 101 }, 'Fetch.requestPaused', {
        requestId: 'req-1',
        request: {
            url: 'https://exam.example.com/quiz',
            headers: {
                'User-Agent': 'Mozilla/5.0 Linux',
                'sec-ch-ua-platform': '"Linux"',
                'X-SafeExamBrowser-ConfigKeyHash': 'stale'
            }
        }
    });
}
await new Promise(resolve => setTimeout(resolve, 20));
const cont = sentCommands.find(c => c.method === 'Fetch.continueRequest');
assert.ok(cont);
const headers = (cont.params as { headers: Array<{ name: string; value: string }> }).headers;
assert.ok(headers.some(h => h.name === 'User-Agent' && h.value.includes('SEB/3.7.0')));
assert.ok(headers.some(h => h.name === 'sec-ch-ua-platform' && h.value === '"Windows"'));
const ckh = headers.filter(h => h.name === 'X-SafeExamBrowser-ConfigKeyHash');
assert.equal(ckh.length, 1);
assert.equal(ckh[0].value, seb.getConfigKey('https://exam.example.com/quiz'));

// 3. Expected detach error during Fetch.continueRequest does NOT emit console.error
const origError = console.error;
const errorLogs: unknown[][] = [];
console.error = (...args: unknown[]) => { errorLogs.push(args); };
try {
    nextSendError = new Error('Debugger is not attached to the tab with id: 101.');
    for (const fn of eventListeners) {
        fn({ tabId: 101 }, 'Fetch.requestPaused', {
            requestId: 'req-2',
            request: { url: 'https://exam.example.com/quiz', headers: {} }
        });
    }
    await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(errorLogs.length, 0);
} finally {
    console.error = origError;
}

// 4. Unexpected error still calls console.error
const errorLogs2: unknown[][] = [];
console.error = (...args: unknown[]) => { errorLogs2.push(args); };
try {
    nextSendError = new Error('Unexpected protocol failure');
    for (const fn of eventListeners) {
        fn({ tabId: 101 }, 'Fetch.requestPaused', {
            requestId: 'req-3',
            request: { url: 'https://exam.example.com/quiz', headers: {} }
        });
    }
    await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(errorLogs2.length, 1);
} finally {
    console.error = origError;
}

handler.disable();
await new Promise(resolve => setTimeout(resolve, 10));
console.log('header-handler checks passed');
