package dev.twme.worldeditsync.common.listener;

import java.util.UUID;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.BaseClipboardManager;

public abstract class BaseMessageListener<T> {
    protected final BaseClipboardManager clipboardManager;

    public BaseMessageListener(BaseClipboardManager clipboardManager) {
        this.clipboardManager = clipboardManager;
    }

    public void handlePluginMessage(T player, byte[] message) {
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();

            switch (subChannel) {
                case "ClipboardUpload":
                    handleClipboardUpload(in);
                    break;
                case "ClipboardDownload":
                    handleClipboardDownload(in, player);
                    break;
                case "ClipboardChunk":
                    handleClipboardChunk(in);
                    break;
                case "ClipboardInfo":
                    handleClipboardInfo(in, player);
                    break;
                case "ClipboardStop":
                    handleClipboardStop(in, player);
                    break;
            }
        } catch (Exception e) {
            handlePluginMessageError(e);
        }
    }

    private void handleClipboardUpload(ByteArrayDataInput in) {
        String playerUuid = in.readUTF();
        String sessionId = in.readUTF();
        int totalChunks = in.readInt();
        int chunkSize = in.readInt();

        if (totalChunks > Constants.MAX_CHUNKS) {
            handleTooManyChunks(totalChunks);
            return;
        }

        clipboardManager.createTransferSession(sessionId,
                UUID.fromString(playerUuid), totalChunks, chunkSize);
    }

    private void handleClipboardChunk(ByteArrayDataInput in) {
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int length = in.readInt();

        if (length <= 0 || length > Constants.DEFAULT_CHUNK_SIZE) {
            handleInvalidChunkSize(length);
            return;
        }

        byte[] chunkData = new byte[length];
        in.readFully(chunkData);

        clipboardManager.addChunk(sessionId, chunkIndex, chunkData);
    }

    private void handleClipboardInfo(ByteArrayDataInput in, T player) {
        String playerUuid = in.readUTF();
        BaseClipboardManager.ClipboardData clipboardData =
                clipboardManager.getClipboard(UUID.fromString(playerUuid));

        if (clipboardData != null) {
            sendClipboardInfo(player, clipboardData.getHash());
        }
    }

    private void handleClipboardStop(ByteArrayDataInput in, T player) {
        String playerUuid = in.readUTF();
        clipboardManager.setPlayerTransferring(UUID.fromString(playerUuid), false);
    }

    protected byte[] createDownloadStartMessage(UUID playerUuid, String sessionId, int totalChunks) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardDownloadStart");
        out.writeUTF(playerUuid.toString());
        out.writeUTF(sessionId);
        out.writeInt(totalChunks);
        out.writeInt(Constants.DEFAULT_CHUNK_SIZE);
        return out.toByteArray();
    }

    protected byte[] createChunkMessage(String sessionId, int chunkIndex, byte[] chunkData) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardChunk");
        out.writeUTF(sessionId);
        out.writeInt(chunkIndex);
        out.writeInt(chunkData.length);
        out.write(chunkData);
        return out.toByteArray();
    }

    protected byte[] createInfoMessage(UUID playerUuid, String hash) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardInfo");
        out.writeUTF(playerUuid.toString());
        out.writeUTF(hash);
        return out.toByteArray();
    }

    // 抽象方法，由平台特定實現提供
    protected abstract void handleClipboardDownload(ByteArrayDataInput in, T player);
    protected abstract void sendClipboardData(T player, byte[] data);
    protected abstract void sendClipboardInfo(T player, String hash);
    protected abstract void handlePluginMessageError(Exception e);
    protected abstract void handleTooManyChunks(int totalChunks);
    protected abstract void handleInvalidChunkSize(int length);
}