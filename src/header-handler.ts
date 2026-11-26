import { MessageResponse } from "./messages";
import { SebFile } from "./seb-tools";

const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 SEB/3.7.0 (x64)";
const SEB_CKH_HTTP_HEADER_NAME = "X-SafeExamBrowser-ConfigKeyHash";
const CDP_VERSION = "1.3";
const KEEP_ALIVE_MS = 20_000;

type Header = { name: string; value: string };

interface RequestPaused {
    requestId: string;
    request: {
        url: string;
        headers?: Record<string, string>;
    };
}

function toCdpPattern(pattern: string): string {
    const trimmed = pattern.trim();
    return trimmed === "<all_urls>" ? "*" : trimmed;
}

export class HeaderHandler {
    private isEnabled = false;
    private sebFile?: SebFile;
    private rqUrlsFilter: string[] = ["<all_urls>"];
    private attachedTabs = new Set<number>();
    private keepAlive?: ReturnType<typeof setInterval>;
    private restorePromise?: Promise<void>;

    constructor() {
        chrome.debugger.onEvent.addListener((source, method, params) => {
            if (method === 'Fetch.requestPaused' && source.tabId !== undefined) {
                void this.onRequestPaused(source.tabId, params as RequestPaused);
            }
        });

        chrome.debugger.onDetach.addListener(source => {
            if (source.tabId !== undefined) {
                this.attachedTabs.delete(source.tabId);
            }
        });
    }

    public setRestorePromise(promise: Promise<void>): void {
        this.restorePromise = promise;
    }

    private patterns() {
        const filter = this.rqUrlsFilter.length > 0 ? this.rqUrlsFilter : ["<all_urls>"];
        return filter.map(pattern => ({ urlPattern: toCdpPattern(pattern) }));
    }

    private async onRequestPaused(tabId: number, event: RequestPaused): Promise<void> {
        if (this.restorePromise) {
            await this.restorePromise;
        }

        const headers: Header[] = [];
        let hasUserAgent = false;

        for (const [name, value] of Object.entries(event.request.headers ?? {})) {
            const lower = name.toLowerCase();
            if (lower === SEB_CKH_HTTP_HEADER_NAME.toLowerCase()) {
                continue;
            }
            if (lower === "user-agent") {
                hasUserAgent = true;
                headers.push({ name, value: USER_AGENT });
                continue;
            }
            if (lower === "sec-ch-ua-platform") {
                headers.push({ name, value: '"Windows"' });
                continue;
            }
            headers.push({ name, value });
        }

        if (!hasUserAgent) {
            headers.push({ name: "User-Agent", value: USER_AGENT });
        }

        try {
            if (this.sebFile) {
                headers.push({ name: SEB_CKH_HTTP_HEADER_NAME, value: this.sebFile.getConfigKey(event.request.url) });
            }
        } catch (err) {
            console.error("Error fetching config key:", err);
        }

        try {
            await chrome.debugger.sendCommand({ tabId }, 'Fetch.continueRequest', {
                requestId: event.requestId,
                headers
            });
        } catch (err) {
            console.error("Error continuing request:", err);
        }
    }

    public async ensureTab(tabId: number): Promise<void> {
        if (this.restorePromise) {
            await this.restorePromise;
        }

        if (!this.isEnabled || this.attachedTabs.has(tabId)) {
            return;
        }

        const tab = await chrome.tabs.get(tabId).catch(() => undefined);
        if (!tab?.url || !/^https?:/i.test(tab.url)) {
            return;
        }

        try {
            await chrome.debugger.attach({ tabId }, CDP_VERSION);
        } catch {
            // already attached (e.g. previous service worker, or DevTools open)
        }

        try {
            await chrome.debugger.sendCommand({ tabId }, 'Fetch.enable', { patterns: this.patterns() });
            this.attachedTabs.add(tabId);
        } catch (err) {
            console.debug("SEB: cannot intercept tab", tabId, err);
        }
    }

    private async ensureAllTabs(): Promise<void> {
        const tabs = await chrome.tabs.query({}).catch(() => []);
        await Promise.all(tabs.filter(tab => tab.id !== undefined).map(tab => this.ensureTab(tab.id!)));
    }

    private async detachAllTabs(): Promise<void> {
        const tabIds = [...this.attachedTabs];
        this.attachedTabs.clear();
        await Promise.all(tabIds.map(async tabId => {
            try {
                await chrome.debugger.sendCommand({ tabId }, 'Fetch.disable');
                await chrome.debugger.detach({ tabId });
            } catch {
                // tab closed or already detached
            }
        }));
    }

    private startKeepAlive(): void {
        this.stopKeepAlive();
        // Chrome >= 110 resets the service worker idle timer on extension API calls.
        this.keepAlive = setInterval(() => { void chrome.runtime.getPlatformInfo(); }, KEEP_ALIVE_MS);
    }

    private stopKeepAlive(): void {
        if (this.keepAlive !== undefined) {
            clearInterval(this.keepAlive);
            this.keepAlive = undefined;
        }
    }

    public updateRqUrlsFilter(rqUrlsFilter: string[]): void {
        this.rqUrlsFilter = rqUrlsFilter;
        if (this.isEnabled) {
            void this.reapplyPatterns();
        }
    }

    private async reapplyPatterns(): Promise<void> {
        await Promise.all([...this.attachedTabs].map(async tabId => {
            try {
                await chrome.debugger.sendCommand({ tabId }, 'Fetch.enable', { patterns: this.patterns() });
            } catch {
                this.attachedTabs.delete(tabId);
            }
        }));
    }

    public enable(sebFile: SebFile, rqUrlsFilter: string[]): void {
        this.sebFile = sebFile;
        this.rqUrlsFilter = rqUrlsFilter;
        this.isEnabled = true;
        this.startKeepAlive();
        void this.ensureAllTabs();
    }

    public disable(): void {
        if (!this.isEnabled) {
            return;
        }
        this.isEnabled = false;
        this.sebFile = undefined;
        this.stopKeepAlive();
        void this.detachAllTabs();
    }

    public getStatus(errorMsg?: string): MessageResponse {
        return {
            enabled: this.isEnabled,
            sebDictionnary: this.sebFile?.Dictionnary,
            sebStartUrl: this.sebFile?.StartUrl,
            rqUrlsFilter: this.rqUrlsFilter,
            errorMsg: errorMsg
        };
    }
}
