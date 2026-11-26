import React, { useState } from 'react';
import ReactDOM from 'react-dom/client';
import { SebFile } from './seb-tools';
import {
    AlertIcon,
    BugIcon,
    CodeIcon,
    CopyButton,
    KeyIcon,
    ShieldIcon,
    UploadIcon
} from './ui-shared';
import './debugging.css';

document.title = 'SEB file debugging';

const App = () => {
    const [fileName, setFileName] = useState<string>('');
    const [url, setUrl] = useState<string>('');
    const [hashedUrl, setHashedUrl] = useState<string>('');
    const [hash, setHash] = useState<string>('');
    const [configHash, setConfigHash] = useState<string>('');
    const [sebFile, setSebFile] = useState<SebFile | null>(null);
    const [parseError, setParseError] = useState<string | null>(null);
    const [isDragging, setIsDragging] = useState<boolean>(false);

    const processFile = (file: File) => {
        setParseError(null);
        const reader = new FileReader();
        reader.onload = async () => {
            const text = reader.result;
            if (typeof text !== 'string') {
                setParseError('Unable to read the selected file.');
                return;
            }
            const sebFileInstance = await SebFile.createInstance(text);
            if (!sebFileInstance) {
                setParseError('Error parsing SEB file. Ensure it is a valid, unencrypted XML plist (.seb / .xml).');
                setSebFile(null);
                setConfigHash('');
                setHash('');
                setHashedUrl('');
                setFileName('');
                return;
            }
            setFileName(file.name);
            setConfigHash(sebFileInstance.ConfigHash);
            setSebFile(sebFileInstance);
            const startUrl = sebFileInstance.StartUrl ?? '';
            setUrl(startUrl);
            setHashedUrl(startUrl);
            setHash(startUrl ? sebFileInstance.getConfigKey(startUrl) : '');
        };
        reader.onerror = () => {
            setParseError('Failed to read the selected file.');
        };
        reader.readAsText(file);
    };

    const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        event.target.value = '';
        if (file) {
            processFile(file);
        }
    };

    const handleDrop = (event: React.DragEvent<HTMLLabelElement>) => {
        event.preventDefault();
        setIsDragging(false);
        const file = event.dataTransfer.files?.[0];
        if (file) {
            processFile(file);
        }
    };

    const handleUrlSubmit = (event?: React.FormEvent) => {
        event?.preventDefault();
        if (sebFile && url) {
            setHashedUrl(url);
            setHash(sebFile.getConfigKey(url));
        }
    };

    const serializedJson = sebFile?.SerializedJson ?? '';

    return (
        <div className="page-wrapper">
            <div className="container">
                <header className="page-header">
                    <div className="brand">
                        <div className="brand-badge">
                            <BugIcon size={20} />
                        </div>
                        <div>
                            <h1>SEB File Debugging</h1>
                            <p className="subtitle">
                                Inspect canonical SEB JSON, Config Hash, and calculate per-URL Config Key hashes.
                            </p>
                        </div>
                    </div>
                    {fileName && (
                        <span className="file-pill" title={fileName}>
                            Loaded: <strong>{fileName}</strong>
                        </span>
                    )}
                </header>

                {parseError && (
                    <div className="alert-error" role="alert">
                        <AlertIcon size={16} className="alert-icon" />
                        <span>{parseError}</span>
                    </div>
                )}

                <section className="card">
                    <div className="card-header">
                        <h2>1. Upload SEB File</h2>
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
                            <UploadIcon size={22} />
                        </div>
                        <div className="dropzone-text">
                            <span className="dropzone-title">Choose a .seb or .xml configuration file</span>
                            <span className="dropzone-hint">or drag and drop it into this area</span>
                        </div>
                    </label>
                </section>

                <section className="card">
                    <div className="card-header">
                        <div className="title-with-icon">
                            <CodeIcon size={15} />
                            <h2>2. Canonical JSON Output</h2>
                        </div>
                        <div className="header-actions">
                            {serializedJson && (
                                <span className="meta-badge">{serializedJson.length} chars</span>
                            )}
                            <CopyButton text={serializedJson} label="Copy JSON" />
                        </div>
                    </div>
                    <p className="field-hint">
                        Sorted alphabetically by key with <code>originatorVersion</code> and empty root dictionaries excluded.
                    </p>
                    <textarea
                        rows={8}
                        value={serializedJson}
                        readOnly
                        spellCheck={false}
                        placeholder="JSON output will appear here"
                        aria-label="Canonical JSON output"
                    />

                    <div className="sub-field">
                        <div className="card-header">
                            <div className="title-with-icon">
                                <ShieldIcon size={15} />
                                <h3>Config Hash (SHA-256)</h3>
                            </div>
                            <CopyButton text={configHash} label="Copy Hash" />
                        </div>
                        <input
                            type="text"
                            className="mono-input"
                            value={configHash}
                            readOnly
                            spellCheck={false}
                            placeholder="Hash output will appear here"
                            aria-label="Config Hash"
                        />
                    </div>
                </section>

                <section className="card">
                    <div className="card-header">
                        <div className="title-with-icon">
                            <KeyIcon size={15} />
                            <h2>3. Get Config Key</h2>
                        </div>
                    </div>
                    <p className="field-hint">
                        Computes <code>SHA256(urlWithoutFragment + configHash)</code> sent in the <code>X-SafeExamBrowser-ConfigKeyHash</code> header.
                    </p>

                    <form className="url-form" onSubmit={handleUrlSubmit}>
                        <input
                            type="text"
                            className="mono-input"
                            value={url}
                            onChange={e => setUrl(e.target.value)}
                            placeholder="Enter request URL (e.g. https://exam.example.com/quiz)"
                            aria-label="Request URL"
                        />
                        <button type="submit" className="btn-primary" disabled={!sebFile || !url}>
                            Get Hash
                        </button>
                    </form>

                    <div className="sub-field">
                        <div className="card-header">
                            <h3 className="truncate-heading" title={hashedUrl ? `Config Key for ${hashedUrl}` : 'Config Key'}>
                                Config Key{hashedUrl ? ` for ${hashedUrl}` : ''}
                            </h3>
                            <CopyButton text={hash} label="Copy Key" />
                        </div>
                        <input
                            type="text"
                            className="mono-input"
                            value={hash}
                            readOnly
                            spellCheck={false}
                            placeholder="Hash output will appear here"
                            aria-label="Config Key output"
                        />
                    </div>
                </section>
            </div>
        </div>
    );
};

ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <App />
    </React.StrictMode>
);
