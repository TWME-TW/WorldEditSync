package dev.twme.worldeditsync.common.transfer;

/**
 * 每位玩家的同步狀態。
 * 用於控制 ClipboardWatcher 的行為以及防止並發操作。
 */
public enum SyncState {

    /** 剛加入/切換伺服器，等待 Proxy 回應 */
    INITIALIZING,

    /** 閒置中，ClipboardWatcher 可偵測變更 */
    IDLE,

    /** 正在上傳剪貼簿至 Proxy */
    UPLOADING,

    /** 正在從 Proxy 下載剪貼簿 */
    DOWNLOADING
}
