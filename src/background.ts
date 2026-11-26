import { HeaderHandler } from './header-handler';
import { EnableRequest, DisableRequest, GetStatusRequest, UpdateRqUrlsFilterRequest } from './messages';
import { SebFile } from './seb-tools';

const STATE_KEY = 'sebState';

interface PersistedState {
    sebXML?: string;
    rqUrlsFilter: string[];
    enabled: boolean;
}

let sebXML: string | undefined;
let rqUrlsFilter: string[] = ["<all_urls>"];

const handler = new HeaderHandler();

async function persist(): Promise<void> {
    const state: PersistedState = { sebXML, rqUrlsFilter, enabled: handler.getStatus().enabled };
    await chrome.storage.session.set({ [STATE_KEY]: state });
}

async function restore(): Promise<void> {
    const stored = (await chrome.storage.session.get(STATE_KEY))?.[STATE_KEY] as PersistedState | undefined;
    if (!stored?.enabled || !stored.sebXML) {
        return;
    }
    const sebFile = await SebFile.createInstance(stored.sebXML);
    if (!sebFile) {
        return;
    }
    sebXML = stored.sebXML;
    rqUrlsFilter = stored.rqUrlsFilter ?? rqUrlsFilter;
    handler.enable(sebFile, rqUrlsFilter);
}

const restorePromise = restore();
handler.setRestorePromise(restorePromise);

async function handleMessage(request: EnableRequest | DisableRequest | GetStatusRequest | UpdateRqUrlsFilterRequest) {
    await restorePromise;
    if (request.action === 'enable' && request.sebXML) {
        const sebFile = await SebFile.createInstance(request.sebXML);
        if (!sebFile) {
            return handler.getStatus('Error parsing SEB file');
        }
        sebXML = request.sebXML;
        rqUrlsFilter = request.rqUrlsFilter;
        handler.enable(sebFile, rqUrlsFilter);
        await persist();
        return handler.getStatus();
    }

    if (request.action === 'disable') {
        handler.disable();
        await persist();
        return handler.getStatus();
    }

    if (request.action === 'getStatus') {
        return handler.getStatus();
    }

    if (request.action === 'updateRqUrlsFilter' && request.rqUrlsFilter) {
        rqUrlsFilter = request.rqUrlsFilter;
        handler.updateRqUrlsFilter(rqUrlsFilter);
        await persist();
        return handler.getStatus();
    }

    return handler.getStatus('Unknown action');
}

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
    handleMessage(request)
        .then(sendResponse)
        .catch((err: unknown) => sendResponse(handler.getStatus(String(err))));
    return true;
});

chrome.tabs.onUpdated.addListener(async tabId => {
    await restorePromise;
    void handler.ensureTab(tabId);
});

chrome.action.onClicked.addListener(() => {
    chrome.windows.create({
        url: chrome.runtime.getURL('popup.html'),
        type: 'popup',
        width: 440,
        height: 680
    });
});
