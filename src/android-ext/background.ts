import { SebFile } from '../seb-tools.ts';

const USER_AGENT =
    'Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 SEB/3.7.0 (x64)';
const SEB_CKH_HTTP_HEADER_NAME = 'X-SafeExamBrowser-ConfigKeyHash';
const NATIVE_APP_ID = 'seb_bypass';

interface WebRequestHeader {
    name: string;
    value?: string;
    binaryValue?: number[];
}

interface BeforeSendHeadersDetails {
    url: string;
    requestHeaders?: WebRequestHeader[];
}

interface RuntimePort {
    onMessage: { addListener: (cb: (msg: unknown) => void) => void };
    onDisconnect: { addListener: (cb: () => void) => void };
    postMessage: (msg: unknown) => void;
}

interface BrowserWebExt {
    runtime: {
        connectNative: (app: string) => RuntimePort;
    };
    webRequest: {
        onBeforeSendHeaders: {
            addListener: (
                cb: (details: BeforeSendHeadersDetails) => { requestHeaders: WebRequestHeader[] } | void,
                filter: { urls: string[] },
                extraInfoSpec: string[]
            ) => void;
            removeListener: (
                cb: (details: BeforeSendHeadersDetails) => { requestHeaders: WebRequestHeader[] } | void
            ) => void;
            hasListener?: (
                cb: (details: BeforeSendHeadersDetails) => { requestHeaders: WebRequestHeader[] } | void
            ) => boolean;
        };
    };
}

declare const browser: BrowserWebExt;

interface NativeCommand {
    action: 'enable' | 'disable' | 'getStatus' | 'updateRqUrlsFilter';
    sebXML?: string;
    rqUrlsFilter?: string[];
}

let sebFile: SebFile | undefined;
let rqUrlsFilter: string[] = ['<all_urls>'];
let isEnabled = false;
let nativePort: RuntimePort | undefined;

function onBeforeSendHeaders(details: BeforeSendHeadersDetails): { requestHeaders: WebRequestHeader[] } {
    const filtered: WebRequestHeader[] = [];
    for (const header of details.requestHeaders ?? []) {
        const lower = header.name.toLowerCase();
        if (lower === SEB_CKH_HTTP_HEADER_NAME.toLowerCase() || lower === 'user-agent') {
            continue;
        }
        if (lower === 'sec-ch-ua-platform') {
            filtered.push({ name: header.name, value: '"Windows"' });
            continue;
        }
        filtered.push(header);
    }

    filtered.push({ name: 'User-Agent', value: USER_AGENT });

    if (sebFile) {
        try {
            filtered.push({
                name: SEB_CKH_HTTP_HEADER_NAME,
                value: sebFile.getConfigKey(details.url)
            });
        } catch (err) {
            console.error('Error computing SEB ConfigKeyHash:', err);
        }
    }

    return { requestHeaders: filtered };
}

function registerWebRequestListener(urls: string[]): void {
    unregisterWebRequestListener();
    const effective = urls.length > 0 ? urls : ['<all_urls>'];
    browser.webRequest.onBeforeSendHeaders.addListener(
        onBeforeSendHeaders,
        { urls: effective },
        ['blocking', 'requestHeaders']
    );
}

function unregisterWebRequestListener(): void {
    try {
        browser.webRequest.onBeforeSendHeaders.removeListener(onBeforeSendHeaders);
    } catch {
        // Ignore if not registered
    }
}

function statusPayload(errorMsg?: string) {
    return {
        action: 'status',
        enabled: isEnabled,
        sebStartUrl: sebFile?.StartUrl ?? '',
        configHash: sebFile?.ConfigHash ?? '',
        rqUrlsFilter,
        userAgent: USER_AGENT,
        errorMsg: errorMsg ?? ''
    };
}

async function handleNativeMessage(raw: unknown): Promise<void> {
    const msg = raw as NativeCommand | undefined;
    if (!msg || typeof msg.action !== 'string') {
        nativePort?.postMessage(statusPayload('Unknown message'));
        return;
    }

    if (msg.action === 'enable' && msg.sebXML) {
        const parsed = await SebFile.createInstance(msg.sebXML);
        if (!parsed) {
            nativePort?.postMessage(statusPayload('Error parsing SEB file'));
            return;
        }
        sebFile = parsed;
        rqUrlsFilter = Array.isArray(msg.rqUrlsFilter) && msg.rqUrlsFilter.length > 0
            ? msg.rqUrlsFilter
            : ['<all_urls>'];
        registerWebRequestListener(rqUrlsFilter);
        isEnabled = true;
        nativePort?.postMessage(statusPayload());
        return;
    }

    if (msg.action === 'disable') {
        unregisterWebRequestListener();
        sebFile = undefined;
        isEnabled = false;
        nativePort?.postMessage(statusPayload());
        return;
    }

    if (msg.action === 'updateRqUrlsFilter' && Array.isArray(msg.rqUrlsFilter)) {
        rqUrlsFilter = msg.rqUrlsFilter.length > 0 ? msg.rqUrlsFilter : ['<all_urls>'];
        if (isEnabled) {
            registerWebRequestListener(rqUrlsFilter);
        }
        nativePort?.postMessage(statusPayload());
        return;
    }

    if (msg.action === 'getStatus') {
        nativePort?.postMessage(statusPayload());
        return;
    }

    nativePort?.postMessage(statusPayload(`Unsupported action: ${msg.action}`));
}

function connectNativeBridge(): void {
    if (typeof browser === 'undefined' || !browser.runtime?.connectNative) {
        return;
    }
    const port = browser.runtime.connectNative(NATIVE_APP_ID);
    nativePort = port;

    port.onMessage.addListener(raw => {
        void handleNativeMessage(raw);
    });

    port.onDisconnect.addListener(() => {
        if (nativePort === port) {
            nativePort = undefined;
        }
        setTimeout(connectNativeBridge, 1000);
    });

    port.postMessage(statusPayload());
}

connectNativeBridge();
