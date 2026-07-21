# WorldEditSync

WorldEditSync lets players copy a WorldEdit selection on one server and paste it on another. It supports Paper, Folia, and Spigot with WorldEdit; Paper servers can also use FastAsyncWorldEdit (FAWE). Synchronization happens automatically without extra player commands.

## What Players Get

- Copy once with `//copy` or `//cut`, then paste on another server with `//paste`.
- Keep the clipboard origin and offset when moving between servers.
- Use the normal WorldEdit or FAWE workflow without learning extra commands.
- Control access with the `worldeditsync.sync` permission, which is granted to everyone by default.

## Choose a Setup

| Setup | Choose this when | Install WorldEditSync on | Extra service |
| --- | --- | --- | --- |
| **Proxy** | Your servers already use BungeeCord or Velocity. This is the simplest option for most proxy networks. | Every backend server and the proxy | None |
| **Database** | You want the backend servers to share clipboards without installing the plugin on a proxy. | Every backend server | Redis, Valkey, KeyDB, MySQL, MariaDB, PostgreSQL, or a shared local SQLite file |
| **S3** | You already run MinIO, AWS S3, or another S3-compatible service. | Every backend server | An S3-compatible bucket |

Only choose one setup. Every participating backend server must use the same setup and settings.

## Before You Start

- Download the latest `WorldEditSync.jar` from the [releases page](https://github.com/TWME-TW/WorldEditSync/releases).
- Install WorldEdit on every Paper, Folia, or Spigot server. Paper servers may use FAWE instead.
- Use the same WorldEditSync version on every participating server and proxy.
- Make sure a player has the same UUID on every backend server. Configure BungeeCord IP forwarding or Velocity player forwarding correctly before testing.
- Use Java 21 or the newer Java version required by your WorldEdit or FAWE download.

Tested combinations for Minecraft 1.21.11:

| Server | WorldEdit 7.4.1 | FAWE 2.15 |
| --- | --- | --- |
| Paper | Supported | Supported |
| Folia | Supported | Not currently available; use WorldEdit |
| Spigot | Supported | Not currently available; use WorldEdit |

Current FAWE releases do not load on Folia and did not start successfully on Spigot 1.21.11 during testing. This is a limitation of FAWE on those server types, not a WorldEditSync configuration issue.

## Install on Backend Servers

1. Stop every participating Paper, Folia, or Spigot server.
2. Put `WorldEditSync.jar` in each server's `plugins` directory.
3. Start each server once so WorldEditSync can create `plugins/WorldEditSync/config.yml`.
4. Stop the servers again before editing the configuration.
5. Follow one of the setup guides below.
6. Restart every participating server after the configuration matches.

The first startup may take longer while required libraries are downloaded. With the default empty token, Proxy mode will remain disabled until configuration is complete; this is expected.

## Shared Token

Set a long, random `token` and use the exact same value on every participating backend server. Proxy mode must also use that value on BungeeCord or Velocity.

You can generate a token on Linux or macOS with:

```sh
openssl rand -hex 32
```

Do not publish the token or commit it to a public repository. Although Database and S3 modes allow an empty token, setting one prevents stored clipboards from being readable by anyone with storage access.

The examples below show only the settings that normally need to change. Leave other generated settings at their defaults unless you have a specific reason to adjust them.

## Proxy Setup

Use this setup when players move between backend servers through BungeeCord or Velocity.

1. Put the same `WorldEditSync.jar` in the proxy's `plugins` directory.
2. Start the proxy once to create its configuration, then stop it.
3. On every backend server, set:

```yaml
sync-mode: "proxy"
token: "replace-with-the-same-random-token"
```

4. Set the same token in the proxy configuration:

   - BungeeCord: `plugins/WorldEditSync/config.yml`
   - Velocity: `plugins/worldeditsync/config.yml`

```yaml
token: "replace-with-the-same-random-token"
```

5. Start the proxy and all backend servers.

Leave the `transfer` settings at their defaults. If you change them later, copy the same values to every backend server and the proxy.

## Database Setup

Database mode only needs WorldEditSync on the backend servers. Do not install it on the proxy for this mode.

First create the database, account, or Redis instance. Every backend server must be able to reach it using the same settings. For MySQL, MariaDB, and PostgreSQL, let the account create its table and read, insert, update, and delete rows in the selected database.

### Redis, Valkey, or KeyDB

Redis is the recommended database option when you are starting from scratch. On every backend server, set:

```yaml
sync-mode: "database"
token: "replace-with-the-same-random-token"

database:
  type: "redis"
  url: "redis://:password@redis-host:6379/0"
```

Remove `:password@` when the Redis server does not use a password. Valkey and KeyDB use the same configuration.

### MySQL, MariaDB, or PostgreSQL

Create an empty database and an account for WorldEditSync. The plugin creates its table automatically.

Example for MariaDB:

```yaml
sync-mode: "database"
token: "replace-with-the-same-random-token"

database:
  type: "mariadb"
  url: ""
  host: "database-host"
  port: 3306
  name: "worldeditsync"
  username: "worldeditsync"
  password: "replace-with-database-password"
```

For another server type, change these values:

| Database | `type` | Usual port |
| --- | --- | --- |
| MySQL | `mysql` | `3306` |
| MariaDB | `mariadb` | `3306` |
| PostgreSQL | `postgresql` | `5432` |

In the generated `database` section, set `ttl-minutes: 0` if stored clipboards should never expire.

### SQLite on One Machine

Use SQLite only when all backend server processes run on the same machine. They must point to one shared local file; the default file inside each individual server directory will not synchronize separate servers.

```yaml
sync-mode: "database"
token: "replace-with-the-same-random-token"

database:
  type: "sqlite"
  url: "jdbc:sqlite:/srv/minecraft/shared/worldeditsync.db"
```

Do not place the SQLite file on NFS or another network filesystem. Use Redis, MySQL/MariaDB, or PostgreSQL when backend servers run on different machines.

## S3 Setup

Use this setup with MinIO, AWS S3, or another compatible service. The credentials need access to read and write objects in the bucket. If the bucket does not exist, the credentials must also allow WorldEditSync to create it.

On every backend server, set:

```yaml
sync-mode: "s3"
token: "replace-with-the-same-random-token"

s3:
  endpoint: "https://s3.example.com"
  access-key: "replace-with-access-key"
  secret-key: "replace-with-secret-key"
  bucket: "worldeditsync"
  region: ""
```

Use an endpoint that every backend server can reach. Set `region` when your provider requires one; it can remain empty for most MinIO installations.

## Check the Installation

1. Start every server involved in synchronization.
2. Check each console for `WorldEditSync enabled` and a message that its sync engine started.
3. Join the first backend server and select a small, asymmetric region.
4. Run `//copy`, then wait a few seconds for the clipboard to upload.
5. Move to the second backend server with the same player account.
6. Wait a few seconds, move to a safe test location, and run `//paste`.
7. Confirm that the blocks and clipboard offset match the original selection.

There are no WorldEditSync commands. Once installed, players continue using normal WorldEdit or FAWE commands.

## Permissions

`worldeditsync.sync` controls who can synchronize clipboards. It is granted to all players by default. Deny this permission with your permissions plugin when only selected builders should use cross-server clipboards.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| WorldEditSync disables itself in Proxy mode | Set a non-empty token on every backend server and the proxy, then restart them. |
| A clipboard never appears on the other server | Confirm both servers use the same mode, token, WorldEditSync version, and player UUID. Wait a few seconds after `//copy`. |
| The console reports decryption or token errors | Copy the exact same token to every participating server. Old stored clipboards created with another token cannot be opened. |
| Database mode keeps retrying | Check the host, port, credentials, firewall, and database permissions from every backend server. |
| S3 mode keeps retrying | Check the endpoint, bucket, credentials, region, and bucket permissions. |
| Velocity or BungeeCord players get different UUIDs | Fix proxy player forwarding before using WorldEditSync. Existing clipboards are stored under the UUID seen by the backend server. |
| A server cannot download libraries on first startup | Allow the server to reach Maven Central, then restart it. |

When asking for help, include the WorldEditSync version, server software and version, WorldEdit or FAWE version, selected sync mode, and relevant console errors. Remove tokens and passwords before sharing configuration or logs.

## Updating

1. Stop the proxy and backend servers.
2. Back up the existing WorldEditSync configuration.
3. Replace the JAR everywhere with the same new version.
4. Review new options in the example configuration.
5. Start the proxy first when using Proxy mode, then start the backend servers.

## For Contributors

Build the plugin with Java 21 and Maven:

```sh
git clone https://github.com/TWME-TW/WorldEditSync.git
cd WorldEditSync
mvn clean package
```

The built JAR is written to `target/WorldEditSync-<version>.jar`.

## Support and License

Open an [issue](https://github.com/TWME-TW/WorldEditSync/issues) for support or bug reports. WorldEditSync is licensed under the [Apache License 2.0](LICENSE).

###### Tags: FastAsyncWorldEdit WorldEdit Clipboard Plugin Copy WorldEditGlobalizer
