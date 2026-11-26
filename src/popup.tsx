import React, { useState, useEffect } from 'react';
import { EnableRequest, DisableRequest, GetStatusRequest, UpdateRqUrlsFilterRequest, MessageResponse } from './messages';
import ReactDOM from 'react-dom/client'
import "./popup.css"

const RQ_URLS_FILTER_DEFAULT = ["<all_urls>"];

const Popup = () => {
    const [response, setResponse] = useState<MessageResponse | null>(null);
    const [tmpUrlsFilter, setTmpUrlsFilter] = useState<string>("");

    const refreshDomFromResponse = (newResponse: MessageResponse) => {
        if (!newResponse) {
            return;
        }
        setTmpUrlsFilter(JSON.stringify(newResponse.rqUrlsFilter ?? RQ_URLS_FILTER_DEFAULT, null, 2));
        setResponse(newResponse);
    }

    const sendMessage = (message: EnableRequest | DisableRequest | GetStatusRequest | UpdateRqUrlsFilterRequest) => {
        chrome.runtime.sendMessage(message, (newResponse: MessageResponse) => {
            if (chrome.runtime.lastError) {
                setResponse({ enabled: false, errorMsg: chrome.runtime.lastError.message });
                return;
            }
            refreshDomFromResponse(newResponse);
        });
    }

    useEffect(() => {
        sendMessage({ action: 'getStatus' });
    }, []);

    const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        if (!file) {
            return;
        }
        const reader = new FileReader();
        reader.onload = () => {
            sendMessage({
                action: 'enable',
                sebXML: reader.result as string,
                rqUrlsFilter: RQ_URLS_FILTER_DEFAULT
            });
        };
        reader.readAsText(file);
    };

    const handleDisable = () => {
        sendMessage({ action: 'disable' });
    };

    const setRqUrlsFilterToDomainStartUrlAndUpdate = () => {
        if (!response?.sebStartUrl) {
            return;
        }
        try {
            const origin = new URL(response.sebStartUrl).origin;
            sendMessage({ action: 'updateRqUrlsFilter', rqUrlsFilter: [`${origin}/*`] });
        }
        catch {
            alert('Invalid start URL');
        }
    }

    const updateRequestUrlsFilterFromInput = () => {
        try {
            const urlsFilter = JSON.parse(tmpUrlsFilter);
            if (!Array.isArray(urlsFilter) || urlsFilter.some(url => typeof url !== 'string')) {
                throw new Error('Expected a JSON array of strings');
            }
            sendMessage({ action: 'updateRqUrlsFilter', rqUrlsFilter: urlsFilter });
        }
        catch {
            alert('Invalid JSON: expected an array of URL patterns');
            if (response) {
                refreshDomFromResponse(response);
            }
        }
    }

    return (
        <div>
            <h1>SEB configuration</h1>
            {response && (
                <>
                    <div className='status'>
                        <div className='key-value'>
                            <div>STATUS</div>
                            <div className='value'>
                                {response.enabled ? '🟢 ENABLED' : '🔴 DISABLED'}
                            </div>
                        </div>
                        {response.errorMsg &&
                            <div className='key-value'>
                                <div>ERROR</div>
                                <div className='value'>
                                    {response.errorMsg}
                                </div>
                            </div>}

                        {response.enabled &&
                            <>
                                <div className='key-value'>
                                    <div>START URL</div>
                                    {response.sebStartUrl &&
                                        <a className='value' href={response.sebStartUrl} target='_blank' rel='noreferrer'>{response.sebStartUrl}</a>
                                    }
                                </div>
                                <div className='key-value'>
                                    <div>RQ URLS FILTER</div>
                                    <div className='value'>
                                        {JSON.stringify(response.rqUrlsFilter)}
                                    </div>
                                </div>
                                <button onClick={handleDisable}>Disable</button>
                            </>
                        }
                    </div>

                    {!response.enabled &&
                        <input type="file" accept=".xml,.seb" onChange={handleFileChange} />
                    }

                    {response.enabled && (
                        <>
                            <h2>Request URLs filter</h2>
                            <textarea
                                className='request-filter-urls'
                                value={tmpUrlsFilter}
                                onChange={(e) => setTmpUrlsFilter(e.target.value)}
                                rows={10}
                                cols={50}
                            />
                            <button onClick={updateRequestUrlsFilterFromInput}>Update</button>
                            <br />
                            {response.sebStartUrl &&
                                <button onClick={setRqUrlsFilterToDomainStartUrlAndUpdate}>
                                    Update and set to the domain of the start URL
                                </button>
                            }
                        </>
                    )}

                    {response.sebDictionnary &&
                        <>
                            <h2>Seb dictionnary</h2>
                            <textarea readOnly className='seb-dict' value={JSON.stringify(response.sebDictionnary, null, 2)} />
                        </>
                    }
                </>
            )}
            <br />
            <a href='debugging.html' target='_blank' rel='noreferrer'>SEB file debugging</a>
        </div>
    );
};

ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <Popup />
    </React.StrictMode>,
)
