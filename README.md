# Night Vision Thermal Camera

Android camera app for low-light viewing on normal phone cameras.

Features:
- Night Vision modes: classic green NVG, White Phosphor NVG, Amber NVG, Low-Light Colour Boost and Moonlight.
- Frame stacking (Stack): motion-aware multi-frame averaging that cancels random noise in dark scenes.
- Enhance: histogram equalization for stronger thermal-style contrast.
- Thermal Style palettes: Ironbow, Rainbow, Lava, Arctic and Medical false-colour palettes made from visible-light brightness, plus White Hot and Black Hot.
- Alarm Red isotherm: highlights only the brightest areas in red/orange.
- Heat Finder and Cold Finder: mark the hottest-looking and coldest-looking spots, estimated from visible light only.
- IR Detector: highlights infrared-like light the phone camera can still see (most infrared is blocked by the phone lens filter).
- Motion Detector, Edge Detect and Focus Peaking modes.
- Spot readings: relative center/hot/cold values, ambient light (lux) readout when the phone has a light sensor, hot/cold spot markers and optional visual-edge overlay (Edges).
- Gain, contrast and zoom controls, torch, front/back camera switch, external camera selection when Android exposes a USB/external camera, freeze frame, grid overlay, and capture to Pictures/NightVisionThermal.
- Includes the 32-bit armeabi-v7a native library, so the APK installs on 32-bit Android phones such as Infinix Smart 9.

Important limit: Thermal Style, Heat Finder and Cold Finder are not real thermal imaging. A normal phone camera cannot measure heat. Real thermal imaging needs an external LWIR sensor such as FLIR Lepton, PureThermal, FLIR One, or a similar USB thermal camera.
