# WorldEditSync

WorldEditSync is a Minecraft plugin that synchronizes WorldEdit (FastAsyncWorldEdit) clipboards across multiple servers. It supports **Proxy mode** (BungeeCord/Velocity Plugin Messages), **S3 mode** (MinIO, AWS S3, and compatible services), and **Database mode** (Redis, MySQL, MariaDB, PostgreSQL, or SQLite).

## Features

- Synchronize WorldEdit and FastAsyncWorldEdit clipboards across multiple servers.
- **Proxy mode**: Sync via BungeeCord or Velocity proxy using Plugin Messages.
- **S3 mode**: Sync via S3-compatible storage (e.g. MinIO) — no proxy plugin required.
- **Database mode**: Sync through Redis/Valkey/KeyDB or a shared SQL database — no proxy plugin required.
- Automatically upload and download clipboards when players switch servers or modify their clipboard.
- Efficient chunk-based data transfer to handle large clipboards.
- AES-256-GCM authenticated encryption for proxy control messages and clipboard data.
- SHA-256 hash comparison to avoid unnecessary transfers.
- Permissions support to control which players can use the synchronization feature.

## Requirements

- Minecraft server running Paper.
- WorldEdit or FastAsyncWorldEdit plugin installed on the Paper server.
- **Proxy mode only**: BungeeCord or Velocity proxy server with WorldEditSync installed.
- **S3 mode only**: An accessible S3-compatible storage service (e.g. MinIO).
- **Database mode only**: A shared Redis-compatible or SQL database. SQLite is limited to Paper processes on the same host sharing one local file.

## Installation

1. **Download the Plugin:**
   - Download the latest version of the WorldEditSync plugin from the [releases page](https://github.com/TWME-TW/WorldEditSync/releases).

2. **Install on Paper Server:**
   - Place the `WorldEditSync.jar` file in the `plugins` directory of your Paper server.
   - Ensure that the WorldEdit or FastAsyncWorldEdit plugin is also installed.

3. **(Proxy mode only) Install on BungeeCord or Velocity Proxy:**
   - Place the `WorldEditSync.jar` file in the `plugins` directory of your BungeeCord or Velocity proxy server.
   - Use the same WorldEditSync build on every Paper server and proxy.

4. **Configuration:**
   - Edit `plugins/WorldEditSync/config.yml` on your Paper server.
   - Set `sync-mode` to `"proxy"` (default), `"s3"`, or `"database"`.
   - If using S3 mode, fill in the `s3` section with your storage endpoint and credentials.
   - In Proxy mode, set the same non-empty `token` on every Paper server and the Proxy. It is required to prevent players from reading or forging plugin messages.
   - In S3 or Database mode, `token` is optional but strongly recommended because an empty token stores plaintext clipboards.

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

### Database Mode

Database mode uses the same asynchronous synchronization, size limits, AES-256-GCM encryption, and SHA-256 integrity checks as S3 mode. Use the same `token` and database settings on every Paper server.

| `database.type` | Default URL | Notes |
| --- | --- | --- |
| `redis` | `redis://127.0.0.1:6379` | Also supports Valkey and KeyDB. Uses pub/sub notifications with polling fallback. |
| `mysql` | `jdbc:mysql://127.0.0.1:3306/worldeditsync` | Uses MySQL Connector/J. |
| `mariadb` | `jdbc:mariadb://127.0.0.1:3306/worldeditsync` | Uses MariaDB Connector/J. |
| `postgresql` | `jdbc:postgresql://127.0.0.1:5432/worldeditsync` | `postgres` is accepted as an alias. |
| `sqlite` | `plugins/WorldEditSync/clipboards.db` | Suitable for Paper processes on one host sharing this local file; do not place the WAL database on NFS. |

Example PostgreSQL configuration:

```yaml
sync-mode: "database"
token: "your-secret-token"

database:
  type: "postgresql"
  host: "127.0.0.1"
  port: 5432
  name: "worldeditsync"
  username: "worldeditsync"
  password: "change-me"
  table: "worldeditsync_clipboards"
  pool-size: 4
  connection-timeout-ms: 10000
  check-interval: 40
  ttl-minutes: 60
```

Set `database.url` to override the generated URL. Redis credentials and database indexes can be included in that URL, for example `redis://:password@127.0.0.1:6379/0`. Set `database.ttl-minutes` to `0` to retain clipboards indefinitely.

The first Paper startup downloads the runtime libraries declared in `plugin.yml`. No database libraries are loaded by BungeeCord or Velocity.

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
