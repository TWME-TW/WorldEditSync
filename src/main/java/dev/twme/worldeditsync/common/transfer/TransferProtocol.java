package dev.twme.worldeditsync.common.transfer;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

/**
 * 傳輸協議：統一定義所有訊息類型與建立方法。
 *
 * 協議流程：
 *
 * <h3>上傳 (Paper → Proxy):</h3>
 * <ol>
 *   <li>Paper 偵測到剪貼簿變更</li>
 *   <li>Paper 發送 {@code Upload:Begin} (uuid, sessionId, totalChunks, totalBytes, hash)</li>
 *   <li>Paper 發送 {@code Upload:Chunk} (sessionId, chunkIndex, data) × N</li>
 *   <li>Proxy 組裝完成並儲存</li>
 * </ol>
 *
 * <h3>下載 (Proxy → Paper):</h3>
 * <ol>
 *   <li>玩家切換伺服器，Proxy 發送 {@code Sync:HashCheck} 或 {@code Sync:NoData}</li>
 *   <li>Paper 比對 hash，若不同則發送 {@code Download:Request}</li>
 *   <li>Proxy 發送 {@code Download:Begin} + {@code Download:Chunk} × N</li>
 *   <li>Paper 組裝、反序列化並套用至 WorldEdit</li>
 * </ol>
 */
public class TransferProtocol {

    // === 訊息子通道名稱 ===

    /** Paper → Proxy：開始上傳剪貼簿 */
    public static final String UPLOAD_BEGIN = "Upload:Begin";

    /** Paper → Proxy：上傳一個 chunk */
    public static final String UPLOAD_CHUNK = "Upload:Chunk";

    /** Paper → Proxy：請求下載剪貼簿 */
    public static final String DOWNLOAD_REQUEST = "Download:Request";

    /** Proxy → Paper：開始下載剪貼簿 */
    public static final String DOWNLOAD_BEGIN = "Download:Begin";

    /** Proxy → Paper：下載一個 chunk */
    public static final String DOWNLOAD_CHUNK = "Download:Chunk";

    /** Proxy → Paper：傳送 hash 供比對 */
    public static final String HASH_CHECK = "Sync:HashCheck";

    /** Proxy → Paper：該玩家在 Proxy 上無剪貼簿資料 */
    public static final String NO_DATA = "Sync:NoData";

    /** 雙向：取消當前傳輸 */
    public static final String CANCEL = "Sync:Cancel";

    // === 訊息建立方法 ===

    public static byte[] createUploadBegin(String uuid, String sessionId, int totalChunks, int totalBytes, String hash) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(UPLOAD_BEGIN);
        out.writeUTF(uuid);
        out.writeUTF(sessionId);
        out.writeInt(totalChunks);
        out.writeInt(totalBytes);
        out.writeUTF(hash);
        return out.toByteArray();
    }

    public static byte[] createUploadChunk(String sessionId, int chunkIndex, byte[] data) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(UPLOAD_CHUNK);
        out.writeUTF(sessionId);
        out.writeInt(chunkIndex);
        out.writeInt(data.length);
        out.write(data);
        return out.toByteArray();
    }

    public static byte[] createDownloadRequest(String uuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(DOWNLOAD_REQUEST);
        out.writeUTF(uuid);
        return out.toByteArray();
    }

    public static byte[] createDownloadBegin(String uuid, String sessionId, int totalChunks, int totalBytes, String hash) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(DOWNLOAD_BEGIN);
        out.writeUTF(uuid);
        out.writeUTF(sessionId);
        out.writeInt(totalChunks);
        out.writeInt(totalBytes);
        out.writeUTF(hash);
        return out.toByteArray();
    }

    public static byte[] createDownloadChunk(String sessionId, int chunkIndex, byte[] data) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(DOWNLOAD_CHUNK);
        out.writeUTF(sessionId);
        out.writeInt(chunkIndex);
        out.writeInt(data.length);
        out.write(data);
        return out.toByteArray();
    }

    public static byte[] createHashCheck(String uuid, String hash) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(HASH_CHECK);
        out.writeUTF(uuid);
        out.writeUTF(hash);
        return out.toByteArray();
    }

    public static byte[] createNoData(String uuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(NO_DATA);
        out.writeUTF(uuid);
        return out.toByteArray();
    }

    public static byte[] createCancel(String uuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(CANCEL);
        out.writeUTF(uuid);
        return out.toByteArray();
    }
}
