package com.habib.nightvisionthermal;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
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
    private ExecutorService executor;
    private ProcessCameraProvider provider;
    private Camera camera;
    private Bitmap lastBitmap;
    private boolean torchOn = false;
    private int cameraMode = 0; // 0 back, 1 front, 2 external if present
    private String externalCameraId;
    private volatile int mode = 1;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        processedView = findViewById(R.id.processedView);
        statusText = findViewById(R.id.statusText);
        modeSpinner = findViewById(R.id.modeSpinner);
        gainBar = findViewById(R.id.gainBar);
        contrastBar = findViewById(R.id.contrastBar);
        executor = Executors.newSingleThreadExecutor();
        findExternalCamera();
        String[] modes = {"Normal Camera", "Night Vision", "Thermal Style: Ironbow", "Thermal Style: Rainbow", "White Hot", "Black Hot", "Edge Detect"};
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        modeSpinner.setSelection(1);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { mode = position; updateModeUi(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        Button capture = findViewById(R.id.captureButton);
        Button torch = findViewById(R.id.torchButton);
        Button cam = findViewById(R.id.cameraButton);
        capture.setOnClickListener(v -> captureImage());
        torch.setOnClickListener(v -> toggleTorch());
        cam.setOnClickListener(v -> switchCamera());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else statusText.setText("Camera permission is needed.");
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
        updateModeUi();
        String camName = cameraMode == 2 ? "external camera" : (cameraMode == 1 ? "front camera" : "back camera");
        statusText.setText("Using " + camName + (externalCameraId != null ? ". External camera detected." : ". No external thermal camera detected."));
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
            if (mode == 0) return;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int step = Math.max(1, width / 360);
            int outW = width / step;
            int outH = height / step;
            int[] lum = new int[outW * outH];
            for (int y = 0; y < outH; y++) {
                int row = y * step * rowStride;
                for (int x = 0; x < outW; x++) {
                    int idx = row + x * step * pixelStride;
                    lum[y * outW + x] = idx < buffer.capacity() ? (buffer.get(idx) & 0xFF) : 0;
                }
            }
            int[] pixels = new int[outW * outH];
            float gain = Math.max(0.5f, gainBar.getProgress() / 100f);
            float contrast = Math.max(0.5f, contrastBar.getProgress() / 100f);
            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    int i = y * outW + x;
                    int v = lum[i];
                    if (mode == 6) v = edgeValue(lum, outW, outH, x, y);
                    v = clamp(Math.round(((v - 128) * contrast + 128) * gain));
                    pixels[i] = palette(v, y);
                }
            }
            Bitmap bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888);
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

    private int edgeValue(int[] lum, int w, int h, int x, int y) {
        if (x <= 0 || y <= 0 || x >= w - 1 || y >= h - 1) return 0;
        int gx = -lum[(y-1)*w+x-1] - 2*lum[y*w+x-1] - lum[(y+1)*w+x-1] + lum[(y-1)*w+x+1] + 2*lum[y*w+x+1] + lum[(y+1)*w+x+1];
        int gy = -lum[(y-1)*w+x-1] - 2*lum[(y-1)*w+x] - lum[(y-1)*w+x+1] + lum[(y+1)*w+x-1] + 2*lum[(y+1)*w+x] + lum[(y+1)*w+x+1];
        return clamp((int) Math.sqrt(gx*gx + gy*gy));
    }

    private int palette(int v, int y) {
        switch (mode) {
            case 1: int g = (y % 3 == 0) ? (int)(v * 0.82f) : v; return Color.rgb((int)(g*0.22f), g, (int)(g*0.32f));
            case 2: return ironbow(v);
            case 3: return Color.HSVToColor(new float[]{(1f - v/255f) * 240f, 1f, Math.max(0.08f, v/255f)});
            case 4: return Color.rgb(v, v, v);
            case 5: int inv = 255 - v; return Color.rgb(inv, inv, inv);
            case 6: return Color.rgb(0, v, (int)(v*0.35f));
            default: return Color.rgb(v, v, v);
        }
    }

    private int ironbow(int v) {
        float t = v / 255f;
        if (t < 0.25f) return lerpColor(Color.BLACK, Color.rgb(70,0,120), t/0.25f);
        if (t < 0.5f) return lerpColor(Color.rgb(70,0,120), Color.rgb(200,0,40), (t-0.25f)/0.25f);
        if (t < 0.75f) return lerpColor(Color.rgb(200,0,40), Color.rgb(255,130,0), (t-0.5f)/0.25f);
        return lerpColor(Color.rgb(255,130,0), Color.rgb(255,255,210), (t-0.75f)/0.25f);
    }

    private int lerpColor(int a, int b, float t) {
        return Color.rgb((int)(Color.red(a)+(Color.red(b)-Color.red(a))*t), (int)(Color.green(a)+(Color.green(b)-Color.green(a))*t), (int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));
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
