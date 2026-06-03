# tpeapp Local Imagery Restriction Blueprint

## Goals
- Fail closed on image/video rendering in hooked target apps.
- Keep model execution fully offline on-device.
- Separate hook/UI thread from inference pipeline through local Binder IPC.
- Prevent common bypass channels (clipboard/share).

## Components

### Server (Main app process)
- `FilterService` (`com.hound.controller.BIND_FILTER_SERVICE`) exposes `IFilterService`.
- `CensorshipEngine` loads `320.ort` via ONNX Runtime Mobile.
- New binder calls:
  - `processImageBytesForDisplay(byte[]) -> byte[]`
  - `hasForbiddenContent(byte[]) -> boolean`

### Client (LSPosed module in target app process)
- `ImageViewHook`: fail-closed image gate with instant lock overlay + delayed fade reveal.
- `ClipboardShareAntiBypassHook`: intercepts clipboard and `ACTION_SEND` media export path.
- `VideoTrackingHook`: 1500ms thumbnail scan loop for `TextureView` playback.

## Phase Mapping

### Phase 1: Local Processor / YOLO Parsing
- Input: `[1,3,320,320]` float RGB in `[0.0, 1.0]`.
- Output: `[1,22,2100]`.
  - Rows `0..3`: `cx, cy, w, h`.
  - Rows `4..21`: 18 class confidences.
- Detection rule:
  - For each box column `i`: pick highest score from rows `4..21`.
  - If class is forbidden and score `>= 0.55`, build box in model space and scale to full-res bitmap.
- Selective blur:
  - Copy original mutable bitmap.
  - Crop each forbidden ROI.
  - Pixelate by downscaling then upscaling with nearest-neighbor.
  - Draw ROI back into destination canvas.

### Phase 2: Fail-safe Image Hook + Animation
- In `beforeHookedMethod`, hook immediately sets `LOCKED / UNVERIFIED` placeholder.
- Hook sends encoded bitmap bytes to `processImageBytesForDisplay`.
- Timeout budget: `200ms`.
- On timeout or service failure: keep lock overlay (no reveal).
- On success: decode returned bitmap, set alpha to `0.0`, animate to `1.0` over `1500ms`.

### Phase 3: Clipboard / Share Anti-bypass
- Clipboard: intercept image-link copy and neutralize to lock text when unverified.
- Share sheet: intercept `ACTION_SEND`, process stream bytes, replace payload with censored bytes.
- Fail-closed behavior: if sanitization cannot be guaranteed, block export path.

### Phase 4: Periodic Video Tracking
- Hook `TextureView` attach/detach lifecycle.
- Every `1500ms`, capture low-res frame (`160x90`), send to `hasForbiddenContent`.
- If forbidden: apply solid black mask.
- Clear mask only after clean result.

## Threading / Memory Safety
- Hooks never block UI thread on inference.
- Binder calls happen in background coroutines with strict timeout.
- Intermediate bitmaps are recycled in `finally` blocks.
- Session and model resources implement `AutoCloseable` and are closed in service teardown.

## Security
- IPC stays package-local through explicit package binding.
- Fail-closed defaults on service unavailability and timeout.
- No external network required for detection/censorship.
