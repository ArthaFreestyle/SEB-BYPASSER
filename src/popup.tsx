import React, { useEffect, useRef, useState } from 'react';
import ReactDOM from 'react-dom/client';
import {
    DisableRequest,
    EnableRequest,
    GetStatusRequest,
    MessageResponse,
    UpdateRqUrlsFilterRequest
} from './messages';
import {
    AlertIcon,
    BugIcon,
    CheckIcon,
    ChevronIcon,
    CodeIcon,
    CopyButton,
    ExternalLinkIcon,
    FilterIcon,
    GlobeIcon,
    PowerIcon,
    ShieldIcon,
    UploadIcon
} from './ui-shared';
import './popup.css';

const RQ_URLS_FILTER_DEFAULT = ['<all_urls>'];

const Popup = () => {
    const [response, setResponse] = useState<MessageResponse | null>(null);
    const [tmpUrlsFilter, setTmpUrlsFilter] = useState<string>('');
    const [uiError, setUiError] = useState<string | null>(null);
    const [filterSaved, setFilterSaved] = useState<boolean>(false);
    const [isDragging, setIsDragging] = useState<boolean>(false);
    const [dictExpanded, setDictExpanded] = useState<boolean>(true);
    const savedTimerRef = useRef<ReturnType<typeof setTimeout>>();

    useEffect(() => {
        return () => {
            if (savedTimerRef.current !== undefined) {
                clearTimeout(savedTimerRef.current);
            }
        };
    }, []);

    const showSavedBadge = () => {
        setFilterSaved(true);
        if (savedTimerRef.current !== undefined) {
            clearTimeout(savedTimerRef.current);
        }
        savedTimerRef.current = setTimeout(() => setFilterSaved(false), 1800);
    };

    const refreshDomFromResponse = (newResponse: MessageResponse) => {
        if (!newResponse) {
            return;
        }
        setTmpUrlsFilter(JSON.stringify(newResponse.rqUrlsFilter ?? RQ_URLS_FILTER_DEFAULT, null, 2));
        setResponse(newResponse);
        setUiError(null);
    };

    const sendMessage = (
        message: EnableRequest | DisableRequest | GetStatusRequest | UpdateRqUrlsFilterRequest,
        onSuccess?: (res: MessageResponse) => void
    ) => {
        if (typeof chrome === 'undefined' || !chrome.runtime?.sendMessage) {
            setResponse({ enabled: false, errorMsg: 'Chrome extension runtime unavailable' });
            return;
        }

        chrome.runtime.sendMessage(message, (newResponse: MessageResponse) => {
            if (chrome.runtime.lastError) {
                setResponse({ enabled: false, errorMsg: chrome.runtime.lastError.message });
                return;
            }
            refreshDomFromResponse(newResponse);
            onSuccess?.(newResponse);
        });
    };

    useEffect(() => {
        sendMessage({ action: 'getStatus' });
    }, []);

    const processSebFile = (file: File) => {
        setUiError(null);
        const reader = new FileReader();
        reader.onload = () => {
            if (typeof reader.result !== 'string') {
                setUiError('Unable to read the selected file.');
                return;
            }
            sendMessage({
                action: 'enable',
                sebXML: reader.result,
                rqUrlsFilter: RQ_URLS_FILTER_DEFAULT
            });
        };
        reader.onerror = () => {
            setUiError('Failed to read the selected file.');
        };
        reader.readAsText(file);
    };

    const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        event.target.value = '';
        if (file) {
            processSebFile(file);
        }
    };

    const handleDrop = (event: React.DragEvent<HTMLLabelElement>) => {
        event.preventDefault();
        setIsDragging(false);
        const file = event.dataTransfer.files?.[0];
        if (file) {
            processSebFile(file);
        }
    };

    const handleDisable = () => {
        sendMessage({ action: 'disable' });
    };

    const setRqUrlsFilterToDomainStartUrlAndUpdate = () => {
        if (!response?.sebStartUrl) {
            return;
        }
        try {
            const parsed = new URL(response.sebStartUrl);
            if (!/^https?:$/i.test(parsed.protocol) || parsed.origin === 'null') {
                throw new Error('Unsupported URL scheme');
            }
            sendMessage({ action: 'updateRqUrlsFilter', rqUrlsFilter: [`${parsed.origin}/*`] }, showSavedBadge);
        } catch {
            setUiError('Invalid start URL in the SEB configuration.');
        }
    };

    const updateRequestUrlsFilterFromInput = () => {
        try {
            const urlsFilter = JSON.parse(tmpUrlsFilter);
            if (!Array.isArray(urlsFilter) || urlsFilter.some(url => typeof url !== 'string')) {
                throw new Error('Expected a JSON array of strings');
            }
            sendMessage({ action: 'updateRqUrlsFilter', rqUrlsFilter: urlsFilter }, showSavedBadge);
        } catch {
            setUiError('Invalid JSON: expected an array of URL pattern strings (e.g. ["https://exam.example.com/*"]).');
        }
    };

    const activeError = uiError || response?.errorMsg;
    const dictJson = response?.sebDictionnary ? JSON.stringify(response.sebDictionnary, null, 2) : '';
    const dictKeyCount = response?.sebDictionnary ? Object.keys(response.sebDictionnary).length : 0;

    return (
        <div className="app-shell">
            <header className="header">
                <div className="brand">
                    <div className={`brand-icon ${response?.enabled ? 'is-active' : ''}`}>
                        <ShieldIcon size={18} />
                    </div>
                    <div>
                        <h1>SEB Configuration</h1>
                        <p className="subtitle">Header &amp; ConfigKeyHash Interceptor</p>
                    </div>
                </div>
                {response && (
                    <span className={`status-pill ${response.enabled ? 'is-enabled' : 'is-disabled'}`}>
                        <span className="status-dot" />
                        {response.enabled ? 'ENABLED' : 'DISABLED'}
                    </span>
                )}
            </header>

            <main className="main-content">
                {!response && (
                    <div className="card loading-card">
                        <span className="spinner" />
                        <span>Connecting to background worker…</span>
                    </div>
                )}

                {activeError && (
                    <div className="alert-error" role="alert">
                        <AlertIcon size={16} className="alert-icon" />
                        <span className="alert-text">{activeError}</span>
                        {uiError && (
                            <button type="button" className="alert-dismiss" onClick={() => setUiError(null)} aria-label="Dismiss error">
                                ×
                            </button>
                        )}
                    </div>
                )}

                {response && !response.enabled && (
                    <section className="card">
                        <div className="card-header">
                            <h2>Load Configuration</h2>
                        </div>
                        <label
                            className={`dropzone ${isDragging ? 'is-dragging' : ''}`}
                            onDragOver={e => {
                                e.preventDefault();
                                setIsDragging(true);
                            }}
                            onDragLeave={() => setIsDragging(false)}
                            onDrop={handleDrop}
                        >
                            <input type="file" accept=".xml,.seb" onChange={handleFileChange} className="sr-only" />
                            <div className="dropzone-icon">
                                <UploadIcon size={24} />
                            </div>
                            <span className="dropzone-title">Choose .seb or .xml file</span>
                            <span className="dropzone-hint">or drag and drop your configuration here</span>
                        </label>
                        <p className="helper-note">
                            Chrome will show a debugging banner while interception is active. Reload the exam tab after enabling.
                        </p>
                    </section>
                )}

                {response?.enabled && (
                    <>
                        <section className="card">
                            <div className="info-list">
                                <div className="info-item">
                                    <span className="info-label">Start URL</span>
                                    <div className="info-value-row">
                                        {response.sebStartUrl ? (
                                            <>
                                                {/^https?:\/\//i.test(response.sebStartUrl) ? (
                                                    <a
                                                        className="start-url-link"
                                                        href={response.sebStartUrl}
                                                        target="_blank"
                                                        rel="noreferrer"
                                                        title={response.sebStartUrl}
                                                    >
                                                        <span className="truncate">{response.sebStartUrl}</span>
                                                        <ExternalLinkIcon size={12} />
                                                    </a>
                                                ) : (
                                                    <span className="start-url-link" title={response.sebStartUrl}>
                                                        <span className="truncate">{response.sebStartUrl}</span>
                                                    </span>
                                                )}
                                                <CopyButton text={response.sebStartUrl} label="Copy" />
                                            </>
                                        ) : (
                                            <span className="muted-value">Not specified in config</span>
                                        )}
                                    </div>
                                </div>

                                <div className="info-item">
                                    <span className="info-label">Active Filter</span>
                                    <div className="chip-list">
                                        {(response.rqUrlsFilter?.length ? response.rqUrlsFilter : RQ_URLS_FILTER_DEFAULT).map((pattern, idx) => (
                                            <code key={`${pattern}-${idx}`} className="filter-chip" title={pattern}>
                                                {pattern}
                                            </code>
                                        ))}
                                    </div>
                                </div>
                            </div>

                            <button type="button" className="btn-danger btn-full" onClick={handleDisable}>
                                <PowerIcon size={15} />
                                <span>Disable</span>
                            </button>
                        </section>

                        <section className="card">
                            <div className="card-header">
                                <div className="card-title-group">
                                    <FilterIcon size={14} />
                                    <h2>Request URLs filter</h2>
                                </div>
                                {filterSaved && (
                                    <span className="saved-badge">
                                        <CheckIcon size={12} />
                                        Updated
                                    </span>
                                )}
                            </div>
                            <textarea
                                className="code-textarea request-filter-urls"
                                value={tmpUrlsFilter}
                                onChange={e => setTmpUrlsFilter(e.target.value)}
                                rows={4}
                                spellCheck={false}
                                aria-label="Request URLs filter JSON"
                            />
                            <div className="button-group">
                                <button type="button" className="btn-primary" onClick={updateRequestUrlsFilterFromInput}>
                                    Update
                                </button>
                                {response.sebStartUrl && (
                                    <button type="button" className="btn-secondary" onClick={setRqUrlsFilterToDomainStartUrlAndUpdate}>
                                        <GlobeIcon size={14} />
                                        <span>Use Start URL Domain</span>
                                    </button>
                                )}
                            </div>
                        </section>

                        {response.sebDictionnary && (
                            <section className="card">
                                <div className="card-header">
                                    <button
                                        type="button"
                                        className="collapse-trigger"
                                        onClick={() => setDictExpanded(prev => !prev)}
                                        aria-expanded={dictExpanded}
                                    >
                                        <CodeIcon size={14} />
                                        <h2>SEB Dictionary</h2>
                                        <span className="count-badge">{dictKeyCount} keys</span>
                                        <ChevronIcon size={14} rotated={dictExpanded} />
                                    </button>
                                    <CopyButton text={dictJson} label="Copy JSON" />
                                </div>
                                {dictExpanded && (
                                    <textarea
                                        readOnly
                                        className="code-textarea seb-dict"
                                        value={dictJson}
                                        spellCheck={false}
                                        aria-label="Parsed SEB dictionary JSON"
                                    />
                                )}
                            </section>
                        )}
                    </>
                )}
            </main>

            <footer className="footer">
                <a className="debug-link" href="debugging.html" target="_blank" rel="noreferrer">
                    <BugIcon size={14} />
                    <span>SEB file debugging</span>
                    <ExternalLinkIcon size={12} />
                </a>
            </footer>
        </div>
    );
};

ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <Popup />
    </React.StrictMode>
);
