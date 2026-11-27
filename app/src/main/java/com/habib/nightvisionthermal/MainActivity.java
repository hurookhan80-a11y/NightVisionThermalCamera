package com.habib.nightvisionthermal;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraFilter;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAMERA = 10;
    private PreviewView previewView;
    private ImageView processedView;
    private TextView statusText;
    private Spinner modeSpinner;
    private SeekBar gainBar;
    private SeekBar contrastBar;
    private SeekBar zoomBar;
    private ExecutorService executor;
    private ProcessCameraProvider provider;
    private Camera camera;
    private Bitmap lastBitmap;
    private boolean torchOn = false;
    private boolean frozen = false;
    private boolean gridOn = false;
    private int cameraMode = 0; // 0 back, 1 front, 2 external if present
    private String externalCameraId;
    private volatile int mode = 1;
    private int[] prevLum;
    private boolean stackOn = true;
    private boolean enhanceOn = true;
    private boolean edgesOn = false;
    private float[] avgLum;
    private float[] avgR;
    private float[] avgG;
    private float[] avgB;
    private volatile float luxValue = -1f;
    private long lastStatusMs = 0;
    private volatile float zoomRatio = 1f;
    private volatile float maxCamZoom = 1f;

    private final String[] modeNames = {
            "Normal Camera", "Night Vision", "White Phosphor NVG", "Amber NVG",
            "Low-Light Colour Boost", "Moonlight", "Thermal Style: Ironbow",
            "Thermal Style: Rainbow", "Thermal Style: Lava", "Thermal Style: Arctic",
            "Medical Thermal", "Alarm Red", "Heat Finder", "Cold Finder",
            "IR Detector", "Motion Detector", "White Hot", "Black Hot", "Edge Detect", "Focus Peaking"
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        processedView = findViewById(R.id.processedView);
        statusText = findViewById(R.id.statusText);
        modeSpinner = findViewById(R.id.modeSpinner);
        gainBar = findViewById(R.id.gainBar);
        contrastBar = findViewById(R.id.contrastBar);
        zoomBar = findViewById(R.id.zoomBar);
        executor = Executors.newSingleThreadExecutor();
        findExternalCamera();
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modeNames));
        modeSpinner.setSelection(1);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { mode = position; prevLum = null; updateModeUi(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                zoomRatio = 1f + progress / 10f;
                if (camera != null) {
                    try { camera.getCameraControl().setZoomRatio(Math.min(zoomRatio, maxCamZoom)); } catch (Exception ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        Button capture = findViewById(R.id.captureButton);
        Button torch = findViewById(R.id.torchButton);
        Button cam = findViewById(R.id.cameraButton);
        Button freeze = findViewById(R.id.freezeButton);
        Button grid = findViewById(R.id.gridButton);
        capture.setOnClickListener(v -> captureImage());
        torch.setOnClickListener(v -> toggleTorch());
        cam.setOnClickListener(v -> switchCamera());
        freeze.setOnClickListener(v -> { frozen = !frozen; freeze.setText(frozen ? "Unfreeze" : "Freeze"); });
        grid.setOnClickListener(v -> { gridOn = !gridOn; grid.setText(gridOn ? "Grid On" : "Grid"); });
        Button stack = findViewById(R.id.stackButton);
        Button enhance = findViewById(R.id.enhanceButton);
        Button edges = findViewById(R.id.edgesButton);
        stack.setOnClickListener(v -> { stackOn = !stackOn; avgLum = null; stack.setText(stackOn ? "Stack On" : "Stack"); });
        enhance.setOnClickListener(v -> { enhanceOn = !enhanceOn; enhance.setText(enhanceOn ? "Enhance On" : "Enhance"); });
        edges.setOnClickListener(v -> { edgesOn = !edgesOn; edges.setText(edgesOn ? "Edges On" : "Edges"); });
        startLightSensor();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else statusText.setText("Camera permission is needed.");
    }

    private void startLightSensor() {
        try {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sm == null) return;
            Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (light == null) return;
            sm.registerListener(new SensorEventListener() {
                @Override public void onSensorChanged(SensorEvent event) { luxValue = event.values[0]; }
                @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            }, light, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception ignored) {}
    }

    private void findExternalCamera() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) return;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) externalCameraId = id;
            }
        } catch (Exception ignored) {}
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try { provider = future.get(); bindCamera(); }
            catch (Exception e) { statusText.setText("Camera start failed: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }

    private CameraSelector selector() {
        if (cameraMode == 2 && externalCameraId != null) {
            return new CameraSelector.Builder().addCameraFilter(new CameraFilter() {
                @Override public List<CameraInfo> filter(List<CameraInfo> cameraInfos) {
                    List<CameraInfo> out = new ArrayList<>();
                    for (CameraInfo info : cameraInfos) {
                        try { if (externalCameraId.equals(Camera2CameraInfo.from(info).getCameraId())) out.add(info); } catch (Exception ignored) {}
                    }
                    return out;
                }
            }).build();
        }
        return cameraMode == 1 ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private void bindCamera() {
        if (provider == null) return;
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(executor, this::analyze);
        provider.unbindAll();
        camera = provider.bindToLifecycle(this, selector(), preview, analysis);
        try {
            if (camera.getCameraInfo().getZoomState().getValue() != null) maxCamZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
            camera.getCameraControl().setZoomRatio(Math.min(zoomRatio, maxCamZoom));
        } catch (Exception ignored) {}
        prevLum = null;
        avgLum = null;
        updateModeUi();
        String camName = cameraMode == 2 ? "external camera" : (cameraMode == 1 ? "front camera" : "back camera");
        statusText.setText(modeNote() + " Using " + camName + (externalCameraId != null ? ". External camera detected." : ". No external thermal camera detected."));
    }

    private String modeNote() {
        switch (mode) {
            case 12: return "Heat Finder estimates from visible light only — not real temperature.";
            case 13: return "Cold Finder estimates from visible light only — not real temperature.";
            case 14: return "IR Detector highlights IR-like light the phone camera can see; most infrared is blocked by the phone lens filter.";
            case 15: return "Motion Detector highlights moving areas in red.";
            default: return "Thermal Style uses visible-light brightness only. Real heat detection needs an external LWIR thermal sensor.";
        }
    }

    private void updateModeUi() {
        boolean processed = mode != 0;
        processedView.setVisibility(processed ? View.VISIBLE : View.GONE);
        previewView.setVisibility(processed ? View.INVISIBLE : View.VISIBLE);
        if (camera != null) {
            try {
                Range<Integer> range = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
                int target = processed ? range.getUpper() : 0;
                camera.getCameraControl().setExposureCompensationIndex(target);
            } catch (Exception ignored) {}
        }
        statusText.setText(modeNote());
    }

    private void switchCamera() {
        cameraMode++;
        if (cameraMode == 2 && externalCameraId == null) cameraMode = 0;
        if (cameraMode > 2) cameraMode = 0;
        bindCamera();
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) { Toast.makeText(this, "Torch not available on this camera", Toast.LENGTH_SHORT).show(); return; }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
    }

    private void analyze(ImageProxy image) {
        try {
            if (mode == 0 || frozen) return;
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
            ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];
            ByteBuffer yBuf = yPlane.getBuffer();
            ByteBuffer uBuf = uPlane.getBuffer();
            ByteBuffer vBuf = vPlane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int yRow = yPlane.getRowStride(); int yPix = yPlane.getPixelStride();
            int uRow = uPlane.getRowStride(); int uPix = uPlane.getPixelStride();
            int vRow = vPlane.getRowStride(); int vPix = vPlane.getPixelStride();
            float crop = zoomRatio / Math.min(zoomRatio, Math.max(1f, maxCamZoom));
            int effW = Math.max(16, Math.round(width / crop));
            int effH = Math.max(16, Math.round(height / crop));
            int startX = (width - effW) / 2;
            int startY = (height - effH) / 2;
            int step = Math.max(1, effW / 360);
            int outW = effW / step;
            int outH = effH / step;
            int n = outW * outH;
            int[] lum = new int[n];
            int[] rr = new int[n]; int[] gg = new int[n]; int[] bb = new int[n];
            for (int y = 0; y < outH; y++) {
                int sy = startY + y * step;
                int yRowOff = sy * yRow;
                int uRowOff = (sy / 2) * uRow;
                int vRowOff = (sy / 2) * vRow;
                for (int x = 0; x < outW; x++) {
                    int sx = startX + x * step;
                    int i = y * outW + x;
                    int yv = sample(yBuf, yRowOff + sx * yPix);
                    int uv = sample(uBuf, uRowOff + (sx / 2) * uPix) - 128;
                    int vv = sample(vBuf, vRowOff + (sx / 2) * vPix) - 128;
                    lum[i] = yv;
                    rr[i] = clamp(Math.round(yv + 1.402f * vv));
                    gg[i] = clamp(Math.round(yv - 0.344f * uv - 0.714f * vv));
                    bb[i] = clamp(Math.round(yv + 1.772f * uv));
                }
            }
            if (stackOn) applyStack(lum, rr, gg, bb, n);
            int[] eqMap = enhanceOn ? equalizeMap(lum, n) : null;
            float gain = Math.max(0.5f, gainBar.getProgress() / 100f);
            float contrast = Math.max(0.5f, contrastBar.getProgress() / 100f);
            int[] pixels = new int[n];
            int markerIdx = -1; int markerScore = -1;
            int hotIdx = 0; int coldIdx = 0;
            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    int i = y * outW + x;
                    if (lum[i] > lum[hotIdx]) hotIdx = i;
                    if (lum[i] < lum[coldIdx]) coldIdx = i;
                    int v = eqMap != null ? eqMap[lum[i]] : lum[i];
                    if (mode == 18 || mode == 19) v = edgeValue(lum, outW, outH, x, y);
                    v = clamp(Math.round(((v - 128) * contrast + 128) * gain));
                    int score = -1;
                    if (mode == 12) score = heatScore(lum[i], rr[i], gg[i], bb[i]);
                    else if (mode == 13) score = coldScore(lum[i], rr[i], gg[i], bb[i]);
                    else if (mode == 14) score = irScore(lum[i], rr[i], gg[i], bb[i]);
                    if (score > markerScore) { markerScore = score; markerIdx = i; }
                    int px = palette(v, y, i, lum, rr, gg, bb, gain, contrast);
                    if (edgesOn && mode >= 6 && mode <= 13) {
                        int e = edgeValue(lum, outW, outH, x, y);
                        if (e > 70) px = Color.rgb(20, 20, 20);
                    }
                    pixels[i] = px;
                }
            }
            if (mode == 15) prevLum = lum.clone();
            int centerVal = lum[(outH / 2) * outW + outW / 2] * 100 / 255;
            int hotVal = lum[hotIdx] * 100 / 255;
            int coldVal = lum[coldIdx] * 100 / 255;
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastStatusMs > 600) {
                lastStatusMs = nowMs;
                String luxText = luxValue >= 0 ? (" Light: " + Math.round(luxValue) + " lux.") : "";
                String spotText = " Spot center " + centerVal + ", hot " + hotVal + ", cold " + coldVal + " (relative).";
                runOnUiThread(() -> statusText.setText(modeNote() + spotText + luxText));
            }
            Bitmap bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888);
            bmp = bmp.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(bmp);
            if (gridOn) drawGrid(canvas, outW, outH);
            if (markerIdx >= 0 && (mode == 12 || mode == 13 || mode == 14)) {
                drawMarker(canvas, markerIdx % outW, markerIdx / outW, mode == 13 ? Color.CYAN : Color.RED);
            }
            if (mode >= 6 && mode <= 11) {
                drawMarker(canvas, hotIdx % outW, hotIdx / outW, Color.RED);
                drawMarker(canvas, coldIdx % outW, coldIdx / outW, Color.CYAN);
            }
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix m = new Matrix(); m.postRotate(rotation);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            Bitmap finalBmp = bmp;
            runOnUiThread(() -> { lastBitmap = finalBmp; processedView.setImageBitmap(finalBmp); });
        } catch (Exception ignored) {
        } finally { image.close(); }
    }

    private void applyStack(int[] lum, int[] rr, int[] gg, int[] bb, int n) {
        if (avgLum == null || avgLum.length != n) {
            avgLum = new float[n]; avgR = new float[n]; avgG = new float[n]; avgB = new float[n];
            for (int i = 0; i < n; i++) { avgLum[i] = lum[i]; avgR[i] = rr[i]; avgG[i] = gg[i]; avgB[i] = bb[i]; }
            return;
        }
        for (int i = 0; i < n; i++) {
            float alpha = Math.abs(lum[i] - avgLum[i]) > 40 ? 0.85f : 0.35f;
            avgLum[i] += (lum[i] - avgLum[i]) * alpha;
            avgR[i] += (rr[i] - avgR[i]) * alpha;
            avgG[i] += (gg[i] - avgG[i]) * alpha;
            avgB[i] += (bb[i] - avgB[i]) * alpha;
            lum[i] = clamp(Math.round(avgLum[i]));
            rr[i] = clamp(Math.round(avgR[i]));
            gg[i] = clamp(Math.round(avgG[i]));
            bb[i] = clamp(Math.round(avgB[i]));
        }
    }

    private int[] equalizeMap(int[] lum, int n) {
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[lum[i]]++;
        int[] map = new int[256];
        int cdf = 0;
        int cdfMin = -1;
        for (int k = 0; k < 256; k++) { cdf += hist[k]; if (cdfMin < 0 && cdf > 0) cdfMin = cdf; map[k] = cdf; }
        if (cdfMin < 0 || n == cdfMin) { for (int k = 0; k < 256; k++) map[k] = k; return map; }
        for (int k = 0; k < 256; k++) map[k] = clamp(Math.round((map[k] - cdfMin) * 255f / (n - cdfMin)));
        return map;
    }

    private int sample(ByteBuffer buf, int idx) { return idx >= 0 && idx < buf.capacity() ? (buf.get(idx) & 0xFF) : 0; }

    private int heatScore(int lum, int r, int g, int b) {
        int redness = r - (g + b) / 2;
        return clamp(Math.round(lum * 0.6f + redness * 0.8f + 40));
    }

    private int coldScore(int lum, int r, int g, int b) {
        int blueness = b - (r + g) / 2;
        return clamp(Math.round((255 - lum) * 0.6f + blueness * 0.8f + 40));
    }

    private int irScore(int lum, int r, int g, int b) {
        return clamp(Math.round(r * 0.9f - g * 0.45f - b * 0.15f + lum * 0.25f));
    }

    private int edgeValue(int[] lum, int w, int h, int x, int y) {
        if (x <= 0 || y <= 0 || x >= w - 1 || y >= h - 1) return 0;
        int gx = -lum[(y-1)*w+x-1] - 2*lum[y*w+x-1] - lum[(y+1)*w+x-1] + lum[(y-1)*w+x+1] + 2*lum[y*w+x+1] + lum[(y+1)*w+x+1];
        int gy = -lum[(y-1)*w+x-1] - 2*lum[(y-1)*w+x] - lum[(y-1)*w+x+1] + lum[(y+1)*w+x-1] + 2*lum[(y+1)*w+x] + lum[(y+1)*w+x+1];
        return clamp((int) Math.sqrt(gx*gx + gy*gy));
    }

    private int palette(int v, int y, int i, int[] lum, int[] rr, int[] gg, int[] bb, float gain, float contrast) {
        switch (mode) {
            case 1: { int g = (y % 3 == 0) ? (int)(v * 0.82f) : v; return Color.rgb((int)(g*0.22f), g, (int)(g*0.32f)); }
            case 2: return Color.rgb((int)(v*0.92f), v, (int)(v*0.94f));
            case 3: return Color.rgb(v, (int)(v*0.68f), (int)(v*0.18f));
            case 4: return colourBoost(rr[i], gg[i], bb[i], gain, contrast);
            case 5: return Color.rgb((int)(v*0.45f), (int)(v*0.65f), clamp((int)(v*1.25f)));
            case 6: return ironbow(v);
            case 7: return Color.HSVToColor(new float[]{(1f - v/255f) * 240f, 1f, Math.max(0.08f, v/255f)});
            case 8: return lava(v);
            case 9: return arctic(v);
            case 10: return medical(v);
            case 11: return v > 205 ? Color.rgb(255, 30, 30) : (v > 165 ? Color.rgb(255, 140, 0) : Color.rgb(v, v, v));
            case 12: return ironbow(heatScore(lum[i], rr[i], gg[i], bb[i]));
            case 13: return arctic(coldScore(lum[i], rr[i], gg[i], bb[i]));
            case 14: { int s = irScore(lum[i], rr[i], gg[i], bb[i]); return s > 150 ? Color.rgb(255, 240, 255) : Color.rgb((int)(s*0.7f), (int)(s*0.2f), s); }
            case 15: {
                int base = (int)(v * 0.55f);
                if (prevLum != null && i < prevLum.length && Math.abs(lum[i] - prevLum[i]) > 28) return Color.rgb(255, 40, 40);
                return Color.rgb(base, base, base);
            }
            case 16: return Color.rgb(v, v, v);
            case 17: { int inv = 255 - v; return Color.rgb(inv, inv, inv); }
            case 18: return Color.rgb(0, v, (int)(v*0.35f));
            case 19: { int base = lum[i]; return v > 60 ? Color.rgb(255, 40, 40) : Color.rgb(base, base, base); }
            default: return Color.rgb(v, v, v);
        }
    }

    private int colourBoost(int r, int g, int b, float gain, float contrast) {
        int r2 = clamp(Math.round(((r - 128) * contrast + 128) * gain));
        int g2 = clamp(Math.round(((g - 128) * contrast + 128) * gain));
        int b2 = clamp(Math.round(((b - 128) * contrast + 128) * gain));
        int avg = (r2 + g2 + b2) / 3;
        return Color.rgb(clamp(Math.round(avg + (r2 - avg) * 1.5f)), clamp(Math.round(avg + (g2 - avg) * 1.5f)), clamp(Math.round(avg + (b2 - avg) * 1.5f)));
    }

    private int ironbow(int v) {
        float t = v / 255f;
        if (t < 0.25f) return lerpColor(Color.BLACK, Color.rgb(70,0,120), t/0.25f);
        if (t < 0.5f) return lerpColor(Color.rgb(70,0,120), Color.rgb(200,0,40), (t-0.25f)/0.25f);
        if (t < 0.75f) return lerpColor(Color.rgb(200,0,40), Color.rgb(255,130,0), (t-0.5f)/0.25f);
        return lerpColor(Color.rgb(255,130,0), Color.rgb(255,255,210), (t-0.75f)/0.25f);
    }

    private int lava(int v) {
        float t = v / 255f;
        if (t < 0.35f) return lerpColor(Color.BLACK, Color.rgb(120,0,0), t/0.35f);
        if (t < 0.7f) return lerpColor(Color.rgb(120,0,0), Color.rgb(255,90,0), (t-0.35f)/0.35f);
        return lerpColor(Color.rgb(255,90,0), Color.rgb(255,240,170), (t-0.7f)/0.3f);
    }

    private int arctic(int v) {
        float t = v / 255f;
        if (t < 0.4f) return lerpColor(Color.rgb(0,10,60), Color.rgb(0,110,200), t/0.4f);
        if (t < 0.75f) return lerpColor(Color.rgb(0,110,200), Color.rgb(80,230,255), (t-0.4f)/0.35f);
        return lerpColor(Color.rgb(80,230,255), Color.WHITE, (t-0.75f)/0.25f);
    }

    private int medical(int v) {
        float t = v / 255f;
        if (t < 0.33f) return lerpColor(Color.rgb(0,0,140), Color.rgb(0,180,90), t/0.33f);
        if (t < 0.66f) return lerpColor(Color.rgb(0,180,90), Color.rgb(240,220,0), (t-0.33f)/0.33f);
        return lerpColor(Color.rgb(240,220,0), Color.rgb(230,30,30), (t-0.66f)/0.34f);
    }

    private int lerpColor(int a, int b, float t) {
        return Color.rgb((int)(Color.red(a)+(Color.red(b)-Color.red(a))*t), (int)(Color.green(a)+(Color.green(b)-Color.green(a))*t), (int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));
    }

    private void drawGrid(Canvas canvas, int w, int h) {
        Paint p = new Paint(); p.setColor(Color.argb(120, 255, 255, 255)); p.setStrokeWidth(1f);
        for (int k = 1; k < 4; k++) { canvas.drawLine(w * k / 4f, 0, w * k / 4f, h, p); canvas.drawLine(0, h * k / 4f, w, h * k / 4f, p); }
        canvas.drawCircle(w / 2f, h / 2f, 18f, p);
    }

    private void drawMarker(Canvas canvas, int x, int y, int color) {
        Paint p = new Paint(); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f);
        canvas.drawCircle(x, y, 16f, p);
        canvas.drawLine(x - 24, y, x + 24, y, p);
        canvas.drawLine(x, y - 24, x, y + 24, p);
    }

    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private void captureImage() {
        Bitmap bmp = mode == 0 ? previewView.getBitmap() : lastBitmap;
        if (bmp == null) { Toast.makeText(this, "No frame yet", Toast.LENGTH_SHORT).show(); return; }
        executor.execute(() -> {
            boolean ok = saveBitmap(bmp);
            runOnUiThread(() -> Toast.makeText(this, ok ? "Saved to Pictures/NightVisionThermal" : "Save failed", Toast.LENGTH_SHORT).show());
        });
    }

    private boolean saveBitmap(Bitmap bmp) {
        String name = "NVT_" + System.currentTimeMillis() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NightVisionThermal");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) { return out != null && bmp.compress(Bitmap.CompressFormat.JPEG, 92, out); }
            } else {
                File dir = new File(getExternalFilesDir(null), "NightVisionThermal"); if (!dir.exists()) dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { return bmp.compress(Bitmap.CompressFormat.JPEG, 92, out); }
            }
        } catch (Exception e) { return false; }
    }

    @Override protected void onDestroy() { super.onDestroy(); if (executor != null) executor.shutdown(); }
}
