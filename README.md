# BetterRegions

A modern WorldGuard extension plugin that adds economy integration, enhanced region management, and advanced protection features.

## Features

## 🏗️ **Region Management**
- **Vertical Expansion**: Automatically expand region selections to full world height
- **Block Limits**: Configurable region size minimum requirements
- **Auto Flags**: Automatically apply configured flags to newly created regions

## 💰 **Economy Integration**
- **Pay-per-block**: Charge players for region creation based on block count
- **Permission-based Pricing**: Control prices with permissions
- **Smart Calculations**: Only charge for new blocks when expanding regions

### 🛡️ **Enhanced Protection**
- **Fire Spread Protection**: Stop fire from spreading across region boundaries
- **Explosion Protection**: Smart protection against explosions in regions
- **Command Restrictions**: Block specific commands in regions where players can't build

## Requirements

- **Minecraft**: 1.21.x
- **Server**: Paper (recommended) or Spigot
- **Java**: 21 or higher
- **Dependencies**:
  - WorldGuard 7.0.13+
  - WorldEdit 7.3.10+
  - Vault

## Installation

1. Download the latest release from the releases page
2. Place `BetterRegions.jar` in your server's `plugins` folder
3. Ensure WorldGuard and WorldEdit are installed
4. Install Vault and an economy plugin if you want economy features
5. Restart your server
6. Configure the plugin in `plugins/BetterRegions/config.yml`

## Usage

### For Players

The plugin seamlessly integrates with existing WorldGuard commands:

```bash
# Create a region (will show cost and require confirmation if economy is enabled)
/rg claim myhouse

# If confirmation is required:
/rg confirm   # Confirm and pay for the region
/rg cancel    # Cancel the action

# Other commands work normally
/rg redefine myregion
```

### For Administrators

```bash
# Reload configuration
/betterregions reload

# Show help
/betterregions help
```

## Building from Source

### Prerequisites
- JDK 21 or higher
- Git

### Build Steps
```bash
git clone https://github.com/demkom58/better-regions.git
cd betterregions
./gradlew shadowJar
```

The compiled plugin will be in `build/libs/BetterRegions.jar`.

### Development Setup
```bash
# Run a test server with the plugin
./gradlew runServer
```

## Support
- **Issues**: [GitHub Issues](https://github.com/demkom58/better-regions/issues)
- **Discussions**: [GitHub Discussions](https://github.com/demkom58/better-regions/discussions)
