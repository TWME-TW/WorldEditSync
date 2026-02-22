# WorldEditSync

WorldEditSync is a Minecraft plugin that synchronizes WorldEdit (FastAsyncWorldEdit) clipboards across multiple servers. It supports two sync modes: **Proxy mode** (via BungeeCord/Velocity Plugin Messages) and **S3 mode** (via any S3-compatible storage such as MinIO or AWS S3).

## Features

- Synchronize WorldEdit and FastAsyncWorldEdit clipboards across multiple servers.
- **Proxy mode**: Sync via BungeeCord or Velocity proxy using Plugin Messages.
- **S3 mode**: Sync via S3-compatible storage (e.g. MinIO) — no proxy plugin required.
- Automatically upload and download clipboards when players switch servers or modify their clipboard.
- Efficient chunk-based data transfer to handle large clipboards.
- AES-256-GCM encryption for all transferred data (configurable via `token`).
- SHA-256 hash comparison to avoid unnecessary transfers.
- Permissions support to control which players can use the synchronization feature.

## Requirements

- Minecraft server running Paper.
- WorldEdit or FastAsyncWorldEdit plugin installed on the Paper server.
- **Proxy mode only**: BungeeCord or Velocity proxy server with WorldEditSync installed.
- **S3 mode only**: An accessible S3-compatible storage service (e.g. MinIO).

## Installation

1. **Download the Plugin:**
   - Download the latest version of the WorldEditSync plugin from the [releases page](https://github.com/TWME-TW/WorldEditSync/releases).

2. **Install on Paper Server:**
   - Place the `WorldEditSync.jar` file in the `plugins` directory of your Paper server.
   - Ensure that the WorldEdit or FastAsyncWorldEdit plugin is also installed.

3. **(Proxy mode only) Install on BungeeCord or Velocity Proxy:**
   - Place the `WorldEditSync.jar` file in the `plugins` directory of your BungeeCord or Velocity proxy server.

4. **Configuration:**
   - Edit `plugins/WorldEditSync/config.yml` on your Paper server.
   - Set `sync-mode` to `"proxy"` (default) or `"s3"`.
   - If using S3 mode, fill in the `s3` section with your storage endpoint and credentials.
   - Set `token` to a shared secret string to enable AES-256-GCM encryption (recommended).

### S3 Mode Configuration Example

```yaml
sync-mode: "s3"
token: "your-secret-token"

s3:
  endpoint: "http://localhost:9000"
  access-key: "minioadmin"
  secret-key: "minioadmin"
  bucket: "worldeditsync"
  region: ""
  check-interval: 40
```

## Usage

- **Permissions:**
  - The plugin uses the `worldeditsync.sync` permission to control which players can use the synchronization feature. By default, this permission is granted to all players.

- **Commands:**
  - There are no commands required. Clipboard synchronization happens automatically when players copy or cut using WorldEdit or FastAsyncWorldEdit.

## Development

### Building from Source

1. **Clone the Repository:**
   ```sh
   git clone https://github.com/TWME-TW/WorldEditSync.git
   cd WorldEditSync
   ```

2. **Build the Plugin:**
   ```sh
   mvn clean package
   ```

3. **Find the JAR:**
   - The built JAR file will be located in the `target` directory.

### Contributing

Contributions are welcome! Please open an issue or submit a pull request on GitHub.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

## Contact

For support or inquiries, please open an issue on GitHub or contact the authors.

###### Tags: FastAsyncWorldEdit WorldEdit Clipboard Plugin Copy WorldEditGlobalizer
