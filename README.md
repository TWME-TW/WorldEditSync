# WorldEditSync

WorldEditSync is a Minecraft plugin that synchronizes WorldEdit clipboards across multiple servers. This plugin is designed to work with both Paper and Velocity servers, ensuring that players' WorldEdit clipboards are consistent no matter which server they are on.

## Features

- Synchronize WorldEdit clipboards across multiple servers.
- Automatically upload and download clipboards when players switch servers.
- Efficient chunk-based data transfer to handle large clipboards.
- Permissions support to control which players can use the synchronization feature.

## Requirements

- Minecraft server running Paper.
- Velocity proxy server.
- WorldEdit plugin installed on the Paper server.

## Installation

1. **Download the Plugin:**
   - Download the latest version of the WorldEditSync plugin from the [releases page](https://github.com/TWME-TW/WorldEditSync/releases).

2. **Install on Paper Server:**
   - Place the `WorldEditSync.jar` file in the `plugins` directory of your Paper server.
   - Ensure that the WorldEdit plugin is also installed on the Paper server.

3. **Install on Velocity Proxy:**
   - Place the `WorldEditSync.jar` file in the `plugins` directory of your Velocity proxy server.

4. **Configuration:**
   - No additional configuration is required. The plugin will automatically register the necessary channels and start synchronizing clipboards.

## Usage

- **Permissions:**
  - The plugin uses the `worldeditsync.sync` permission to control which players can use the synchronization feature. By default, this permission is granted to all players.

- **Commands:**
  - There are no commands required to use this plugin. Clipboard synchronization happens automatically when players copy or cut using WorldEdit and switch servers.

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
