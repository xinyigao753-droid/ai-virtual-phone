export type DownloadFileOptions = {
    disableNativeShare?: boolean;
    nativeShareOnly?: boolean;
};

export function isAndroidBrowser(): boolean {
    return typeof navigator !== "undefined" && /Android/i.test(navigator.userAgent);
}

export function isIOSBrowser(): boolean {
    if (typeof navigator === "undefined") return false;
    const ua = navigator.userAgent || "";
    const platform = navigator.platform || "";
    return /iPad|iPhone|iPod/i.test(ua) || (platform === "MacIntel" && navigator.maxTouchPoints > 1);
}

type AndroidShellDownloadBridge = {
    beginFileDownload?: (filename: string, mimeType: string) => string;
    appendFileDownloadChunk?: (id: string, base64: string) => boolean;
    finishFileDownload?: (id: string) => boolean;
    cancelFileDownload?: (id: string) => void;
};

function bytesToBase64(bytes: Uint8Array): string {
    let binary = "";
    const step = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += step) {
        binary += String.fromCharCode(...bytes.subarray(offset, offset + step));
    }
    return btoa(binary);
}

async function downloadWithAndroidShell(blob: Blob, filename: string): Promise<boolean> {
    const bridge = (window as typeof window & { AndroidShell?: AndroidShellDownloadBridge }).AndroidShell;
    if (!bridge?.beginFileDownload || !bridge.appendFileDownloadChunk || !bridge.finishFileDownload) return false;
    const id = bridge.beginFileDownload(filename, blob.type || "application/octet-stream");
    if (!id) throw new Error("无法创建安卓下载文件。");
    try {
        const chunkBytes = 256 * 1024;
        for (let offset = 0; offset < blob.size; offset += chunkBytes) {
            const bytes = new Uint8Array(await blob.slice(offset, offset + chunkBytes).arrayBuffer());
            if (!bridge.appendFileDownloadChunk(id, bytesToBase64(bytes))) {
                throw new Error("写入安卓下载文件失败。");
            }
        }
        if (!bridge.finishFileDownload(id)) throw new Error("完成安卓下载文件失败。");
        return true;
    } catch (error) {
        bridge.cancelFileDownload?.(id);
        throw error;
    }
}

export async function downloadFile(blob: Blob, filename: string, options: DownloadFileOptions = {}): Promise<void> {
    if (typeof window !== "undefined" && await downloadWithAndroidShell(blob, filename)) return;
    const url = URL.createObjectURL(blob);
    const anchorDownload = () => {
        const a = document.createElement("a");
        a.href = url;
        a.download = filename;
        a.rel = "noopener";
        document.body.appendChild(a);
        a.click();
        a.remove();
    };

    const shouldUseNativeShare = options.nativeShareOnly || (!options.disableNativeShare && isIOSBrowser());
    if (shouldUseNativeShare) {
        const file = new File([blob], filename, { type: blob.type || "application/octet-stream" });
        const canNativeShare = typeof navigator !== "undefined"
            && typeof navigator.share === "function"
            && typeof navigator.canShare === "function"
            && navigator.canShare({ files: [file] });
        if (canNativeShare) {
            try {
                await navigator.share({ files: [file] });
                setTimeout(() => URL.revokeObjectURL(url), 1000);
                return;
            } catch (err) {
                // User explicitly dismissed the share sheet → respect it, don't force a download.
                if (err instanceof DOMException && err.name === "AbortError") {
                    setTimeout(() => URL.revokeObjectURL(url), 1000);
                    return;
                }
                // Any other failure (webview without real file-share support, lost user
                // activation, etc.) is surfaced to the caller on iOS instead of opening
                // the blob URL, which can navigate away from the app.
            }
        }
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        throw new Error("当前浏览器没有成功打开系统分享，请在 Safari 中重试，或导出轻量备份后再试。");
    }

    anchorDownload();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function downloadUrl(url: string, filename: string): Promise<void> {
    let blob: Blob | null = null;

    try {
        const res = await fetch(url);
        if (res.ok) blob = await res.blob();
    } catch { /* CORS or network error — try proxy */ }

    if (!blob && /^https?:\/\//.test(url)) {
        try {
            const res = await fetch("/api/tool-proxy", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ url, method: "GET" }),
            });
            if (res.ok) blob = await res.blob();
        } catch { /* proxy also failed */ }
    }

    if (blob) {
        await downloadFile(blob, filename);
    } else {
        const a = document.createElement("a");
        a.href = url;
        a.target = "_blank";
        a.rel = "noopener";
        document.body.appendChild(a);
        a.click();
        a.remove();
    }
}
