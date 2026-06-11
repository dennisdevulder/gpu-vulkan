# Source Layout

Most implementation classes still use the shared `com.gpuvulkan` Java package,
but the source files are grouped into concern-based directories:

- `backend/` - Vulkan device, swapchain, render targets, buffers, textures,
  frame recording, and the internal `gfx` adapter implementations.
- `platform/` - X11, Win32, and macOS surface/native-window integration.
- `scene/` - scene capture, model emission, sorting, dirty zones, draw
  scheduling, and stock-scene renderer implementation.
- `extension/` - render-extension registry, frame/context contracts, and
  built-in extension implementations.
- `debug/` - counters, snapshots, resize tracing, and overlay UI.
- `util/` - small shared helpers that do not own renderer state.
- `gfx/` - public rendering facade interfaces.
- `regions/` - region metadata and lookup table.

The shared Java package is intentional for now. The renderer still relies on
package-private collaboration between tightly coupled internals; turning these
folders into real Java subpackages should be done gradually with explicit API
boundaries rather than as a mechanical file move.
