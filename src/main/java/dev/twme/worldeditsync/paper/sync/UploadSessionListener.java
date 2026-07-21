package dev.twme.worldeditsync.paper.sync;

import org.bukkit.entity.Player;

/**
 * Receives proxy control messages for an outbound clipboard upload.
 */
public interface UploadSessionListener {

    void onUploadReady(Player player, String sessionId);

    void onUploadAcknowledged(Player player, String sessionId);

    void onUploadCancelled(Player player, String sessionId, String reason);

    void onDownloadFailed(Player player, String reason);
}
