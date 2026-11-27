package com.habib.nightvisionthermal;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import android.view.Surface;
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
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.MeteringPoint;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private volatile int quality = 0;
    private volatile int motionThreshold = 30;
    private volatile boolean motionPhoto = false;
    private int[] motionHeat;
    private long lastMotionCaptureMs = 0;
    private long lastProcessMs = 0;
    private final int[] eqMap = new int[256];
    private int frameCount = 0;
    private volatile boolean objectBoxOn = true;
    private volatile float areaBrightness = 1.6f;
    private volatile boolean stampOn = false;
    private volatile boolean splitOn = false;
    private boolean redUiOn = false;
    private int strobeMode = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable strobeRunnable;
    private int sosIndex = 0;
    private volatile float focusNormX = -1f;
    private volatile float focusNormY = -1f;
    private volatile long focusTimeMs = 0;
    private volatile boolean recording = false;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private Surface encoderSurface;
    private ParcelFileDescriptor videoPfd;
    private int videoTrack = -1;
    private boolean muxerStarted = false;
    private long recordStartNs = 0;
    private int recW = 320;
    private int recH = 240;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private final String[] modeNames = {
            "Normal Camera", "Night Vision", "White Phosphor NVG", "Amber NVG",
            "Low-Light Colour Boost", "Moonlight", "Thermal Style: Ironbow",
            "Thermal Style: Rainbow", "Thermal Style: Lava", "Thermal Style: Arctic",
            "Medical Thermal", "Alarm Red", "Heat Finder", "Cold Finder",
            "IR Detector", "Motion Detector", "White Hot", "Black Hot", "Edge Detect", "Focus Peaking",
            "Ultra Night Vision + Object + Edge", "Ultra Super Night Vision + Motion"
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        processedView = findViewById(R.id.processedView);
        statusText = findViewById(R.id.statusText);
        for (int i = 0; i < 256; i++) eqMap[i] = i;
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
        ((Button) findViewById(R.id.settingsButton)).setOnClickListener(v -> {
            View panel = findViewById(R.id.settingsPanel);
            boolean show = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(show ? View.VISIBLE : View.GONE);
            ((Button) v).setText(show ? "Close Settings" : "Settings");
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
        ((Button) findViewById(R.id.qualityButton)).setOnClickListener(v -> {
            quality = (quality + 1) % 3;
            ((Button) v).setText(quality == 0 ? "Speed: Fast" : quality == 1 ? "Speed: Balanced" : "Speed: Quality");
            prevLum = null; avgLum = null; motionHeat = null;
        });
        ((Button) findViewById(R.id.motionPhotoButton)).setOnClickListener(v -> {
            motionPhoto = !motionPhoto;
            ((Button) v).setText(motionPhoto ? "Motion Photo: On" : "Motion Photo: Off");
        });
        ((SeekBar) findViewById(R.id.motionBar)).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { motionThreshold = 80 - progress; }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        ((Button) findViewById(R.id.recordButton)).setOnClickListener(v -> toggleRecording((Button) v));
        ((Button) findViewById(R.id.galleryButton)).setOnClickListener(v -> openGallery());
        ((Button) findViewById(R.id.redUiButton)).setOnClickListener(v -> {
            redUiOn = !redUiOn;
            findViewById(R.id.redOverlay).setVisibility(redUiOn ? View.VISIBLE : View.GONE);
            statusText.setTextColor(redUiOn ? Color.RED : Color.WHITE);
            ((Button) v).setText(redUiOn ? "Red UI: On" : "Red UI: Off");
        });
        ((Button) findViewById(R.id.strobeButton)).setOnClickListener(v -> cycleStrobe((Button) v));
        ((Button) findViewById(R.id.stampButton)).setOnClickListener(v -> {
            stampOn = !stampOn;
            ((Button) v).setText(stampOn ? "Stamp: On" : "Stamp: Off");
        });
        ((Button) findViewById(R.id.splitButton)).setOnClickListener(v -> {
            splitOn = !splitOn;
            ((Button) v).setText(splitOn ? "Split: On" : "Split: Off");
        });
        View.OnTouchListener tapFocus = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && v.getWidth() > 0 && v.getHeight() > 0) {
                focusNormX = Math.max(0f, Math.min(1f, event.getX() / v.getWidth()));
                focusNormY = Math.max(0f, Math.min(1f, event.getY() / v.getHeight()));
                focusTimeMs = System.currentTimeMillis();
                applyTapFocus();
                return true;
            }
            return true;
        };
        previewView.setOnTouchListener(tapFocus);
        processedView.setOnTouchListener(tapFocus);
        ((Button) findViewById(R.id.objectBoxButton)).setOnClickListener(v -> {
            objectBoxOn = !objectBoxOn;
            ((Button) v).setText(objectBoxOn ? "Object Box: On" : "Object Box: Off");
        });
        ((SeekBar) findViewById(R.id.areaBar)).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { areaBrightness = Math.max(0.3f, progress / 100f); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
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

    private void setTorch(boolean on) {
        try { if (camera != null && camera.getCameraInfo().hasFlashUnit()) camera.getCameraControl().enableTorch(on); } catch (Exception ignored) {}
    }

    private void cycleStrobe(Button button) {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) { Toast.makeText(this, "Torch not available on this camera", Toast.LENGTH_SHORT).show(); return; }
        if (strobeRunnable != null) handler.removeCallbacks(strobeRunnable);
        strobeMode = (strobeMode + 1) % 3;
        sosIndex = 0;
        if (strobeMode == 0) { setTorch(false); button.setText("Strobe: Off"); return; }
        button.setText(strobeMode == 1 ? "Strobe: On" : "Strobe: SOS");
        strobeRunnable = new Runnable() {
            @Override public void run() {
                if (strobeMode == 1) {
                    torchOn = !torchOn;
                    setTorch(torchOn);
                    handler.postDelayed(this, 180);
                } else if (strobeMode == 2) {
                    int[] pattern = {150, 150, 150, 150, 150, 450, 450, 150, 450, 150, 450, 450, 150, 150, 150, 150, 150, 900};
                    boolean on = sosIndex % 2 == 0;
                    setTorch(on);
                    int delay = pattern[sosIndex % pattern.length];
                    sosIndex++;
                    handler.postDelayed(this, delay);
                }
            }
        };
        handler.post(strobeRunnable);
    }

    private void applyTapFocus() {
        if (camera == null || previewView.getWidth() == 0 || previewView.getHeight() == 0) return;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(focusNormX * previewView.getWidth(), focusNormY * previewView.getHeight());
            FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception ignored) {}
    }

    private void openGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Gallery open failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleRecording(Button button) {
        if (recording) {
            stopRecording();
            button.setText("Record: Off");
            return;
        }
        if (mode == 0) { Toast.makeText(this, "Video recording works in processed modes", Toast.LENGTH_SHORT).show(); return; }
        int w = lastBitmap != null ? lastBitmap.getWidth() : 320;
        int h = lastBitmap != null ? lastBitmap.getHeight() : 240;
        if (startRecording(w, h)) button.setText("Record: On");
    }

    private boolean startRecording(int width, int height) {
        try {
            recW = Math.max(160, width - (width % 2));
            recH = Math.max(120, height - (height % 2));
            String name = "NVT_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            if (Build.VERSION.SDK_INT >= 26) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NightVisionThermal");
                Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("MediaStore insert failed");
                videoPfd = getContentResolver().openFileDescriptor(uri, "rw");
                muxer = new MediaMuxer(videoPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "NightVisionThermal");
                if (!dir.exists()) dir.mkdirs();
                muxer = new MediaMuxer(new File(dir, name).getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, recW, recH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(400000, recW * recH * 4));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();
            videoTrack = -1;
            muxerStarted = false;
            recordStartNs = System.nanoTime();
            recording = true;
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopRecording();
            return false;
        }
    }

    private void encodeFrame(Bitmap bmp) {
        if (!recording || encoder == null || encoderSurface == null) return;
        try {
            Canvas canvas = encoderSurface.lockCanvas(null);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bmp, null, new android.graphics.Rect(0, 0, recW, recH), null);
            encoderSurface.unlockCanvasAndPost(canvas);
            drainEncoder(false);
        } catch (Exception ignored) {}
    }

    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) return;
        try {
            if (endOfStream) encoder.signalEndOfInputStream();
            while (true) {
                int status = encoder.dequeueOutputBuffer(bufferInfo, endOfStream ? 10000 : 0);
                if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break;
                } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) break;
                    videoTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (status >= 0) {
                    java.nio.ByteBuffer data = encoder.getOutputBuffer(status);
                    if (data != null && muxerStarted && bufferInfo.size > 0) {
                        bufferInfo.presentationTimeUs = Math.max(0, (System.nanoTime() - recordStartNs) / 1000);
                        data.position(bufferInfo.offset);
                        data.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(videoTrack, data, bufferInfo);
                    }
                    encoder.releaseOutputBuffer(status, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopRecording() {
        boolean wasRecording = recording;
        recording = false;
        try { drainEncoder(true); } catch (Exception ignored) {}
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        try { if (encoderSurface != null) encoderSurface.release(); } catch (Exception ignored) {}
        try { if (videoPfd != null) videoPfd.close(); } catch (Exception ignored) {}
        encoder = null; muxer = null; encoderSurface = null; videoPfd = null; muxerStarted = false; videoTrack = -1;
        if (wasRecording) Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show();
    }

    private void analyze(ImageProxy image) {
        try {
            if (mode == 0 || frozen) return;
            long nowMs = System.currentTimeMillis();
            long interval = quality == 0 ? 66 : quality == 1 ? 50 : 33;
            if (nowMs - lastProcessMs < interval) return;
            lastProcessMs = nowMs;
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
            int targetW = (mode == 18 || mode == 19) ? 360 : quality == 0 ? 240 : quality == 1 ? 320 : 360;
            int step = Math.max(1, effW / targetW);
            boolean needRgb = mode == 4 || mode == 12 || mode == 13 || mode == 14 || splitOn;
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
                    lum[i] = yv;
                    if (needRgb) {
                        int uv = sample(uBuf, uRowOff + (sx / 2) * uPix) - 128;
                        int vv = sample(vBuf, vRowOff + (sx / 2) * vPix) - 128;
                        rr[i] = clamp(Math.round(yv + 1.402f * vv));
                        gg[i] = clamp(Math.round(yv - 0.344f * uv - 0.714f * vv));
                        bb[i] = clamp(Math.round(yv + 1.772f * uv));
                    } else { rr[i] = yv; gg[i] = yv; bb[i] = yv; }
                }
            }
            if (stackOn && mode != 18 && mode != 19) applyStack(lum, rr, gg, bb, n, needRgb);
            frameCount++;
            if ((enhanceOn || mode == 21) && frameCount % 6 == 0) System.arraycopy(equalizeMap(lum, n), 0, eqMap, 0, 256);
            int[] activeEq = (enhanceOn || mode == 21) ? eqMap : null;
            float gain = Math.max(0.5f, gainBar.getProgress() / 100f);
            float contrast = Math.max(0.5f, contrastBar.getProgress() / 100f);
            boolean motionAlert = false;
            if (mode == 15 || mode == 20 || mode == 21) {
                if (motionHeat == null || motionHeat.length != n) motionHeat = new int[n];
                int cnt = 0;
                if (prevLum != null && prevLum.length == n) {
                    for (int i = 0; i < n; i++) {
                        motionHeat[i] = motionHeat[i] * 4 / 5;
                        if (Math.abs(lum[i] - prevLum[i]) > motionThreshold) { motionHeat[i] = 255; cnt++; }
                    }
                }
                motionAlert = cnt > Math.max(8, n / 250);
            }
            int[] pixels = new int[n];
            int markerIdx = -1; int markerScore = -1;
            int hotIdx = 0; int coldIdx = 0;
            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    int i = y * outW + x;
                    if (lum[i] > lum[hotIdx]) hotIdx = i;
                    if (lum[i] < lum[coldIdx]) coldIdx = i;
                    int v = activeEq != null ? activeEq[lum[i]] : lum[i];
                    if (mode == 18 || mode == 19) { int e = edgeValue(lum, outW, outH, x, y); v = e < 22 ? 0 : clamp((e - 22) * 3); }
                    v = clamp(Math.round(((v - 128) * contrast + 128) * gain));
                    int score = -1;
                    if (mode == 12) score = heatScore(lum[i], rr[i], gg[i], bb[i]);
                    else if (mode == 13) score = coldScore(lum[i], rr[i], gg[i], bb[i]);
                    else if (mode == 14) score = irScore(lum[i], rr[i], gg[i], bb[i]);
                    if (score > markerScore) { markerScore = score; markerIdx = i; }
                    if (mode == 20) v = clamp(Math.round(v * 1.25f));
                    if (mode == 21) v = clamp(Math.round(255f * (float) Math.pow(v / 255f, 1f / Math.max(0.3f, areaBrightness)) * 1.15f));
                    int px = palette(v, y, i, lum, rr, gg, bb, gain, contrast);
                    if (mode == 20) {
                        int e = edgeValue(lum, outW, outH, x, y);
                        if (e > 55) px = Color.rgb(180, 255, 255);
                    }
                    if (edgesOn && mode >= 6 && mode <= 13) {
                        int e = edgeValue(lum, outW, outH, x, y);
                        if (e > 70) px = Color.rgb(20, 20, 20);
                    }
                    if (splitOn && x >= outW / 2) px = Color.rgb(rr[i], gg[i], bb[i]);
                    pixels[i] = px;
                }
            }
            if (mode == 15 || mode == 20 || mode == 21) prevLum = lum.clone();
            int centerVal = lum[(outH / 2) * outW + outW / 2] * 100 / 255;
            int hotVal = lum[hotIdx] * 100 / 255;
            int coldVal = lum[coldIdx] * 100 / 255;
            if (nowMs - lastStatusMs > 600) {
                lastStatusMs = nowMs;
                String luxText = luxValue >= 0 ? (" Light: " + Math.round(luxValue) + " lux.") : "";
                String spotText = " Spot center " + centerVal + ", hot " + hotVal + ", cold " + coldVal + " (relative)." + (motionAlert ? " Motion detected!" : "");
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
            if (motionAlert) {
                Paint border = new Paint(); border.setColor(Color.RED); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(6f);
                canvas.drawRect(3, 3, outW - 3, outH - 3, border);
            }
            if (mode == 20 && objectBoxOn) drawObjectBoxes(canvas, outW, outH);
            if (splitOn) {
                Paint divider = new Paint(); divider.setColor(Color.WHITE); divider.setStrokeWidth(3f);
                canvas.drawLine(outW / 2f, 0, outW / 2f, outH, divider);
            }
            if (focusNormX >= 0 && nowMs - focusTimeMs < 1200) {
                Paint ring = new Paint(); ring.setColor(Color.YELLOW); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(4f);
                canvas.drawCircle(focusNormX * outW, focusNormY * outH, Math.max(14f, outW / 18f), ring);
            }
            if (stampOn) {
                Paint stamp = new Paint(); stamp.setColor(Color.WHITE); stamp.setTextSize(Math.max(13f, outW / 22f)); stamp.setShadowLayer(3f, 1f, 1f, Color.BLACK);
                canvas.drawText(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()), 8, outH - 8, stamp);
            }
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix m = new Matrix(); m.postRotate(rotation);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            Bitmap finalBmp = bmp;
            if (recording) encodeFrame(finalBmp);
            if (motionAlert && motionPhoto && nowMs - lastMotionCaptureMs > 3000) {
                lastMotionCaptureMs = nowMs;
                saveBitmap(finalBmp);
            }
            runOnUiThread(() -> { lastBitmap = finalBmp; processedView.setImageBitmap(finalBmp); });
        } catch (Exception ignored) {
        } finally { image.close(); }
    }

    private void applyStack(int[] lum, int[] rr, int[] gg, int[] bb, int n, boolean needRgb) {
        if (avgLum == null || avgLum.length != n) {
            avgLum = new float[n]; avgR = new float[n]; avgG = new float[n]; avgB = new float[n];
            for (int i = 0; i < n; i++) { avgLum[i] = lum[i]; avgR[i] = rr[i]; avgG[i] = gg[i]; avgB[i] = bb[i]; }
            return;
        }
        for (int i = 0; i < n; i++) {
            float alpha = Math.abs(lum[i] - avgLum[i]) > 40 ? 0.85f : 0.35f;
            avgLum[i] += (lum[i] - avgLum[i]) * alpha;
            lum[i] = clamp(Math.round(avgLum[i]));
            if (needRgb) {
                avgR[i] += (rr[i] - avgR[i]) * alpha;
                avgG[i] += (gg[i] - avgG[i]) * alpha;
                avgB[i] += (bb[i] - avgB[i]) * alpha;
                rr[i] = clamp(Math.round(avgR[i]));
                gg[i] = clamp(Math.round(avgG[i]));
                bb[i] = clamp(Math.round(avgB[i]));
            }
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
                if (motionHeat != null && i < motionHeat.length) {
                    if (motionHeat[i] > 180) return Color.rgb(255, 40, 40);
                    if (motionHeat[i] > 80) return Color.rgb(255, 140, 0);
                }
                return Color.rgb(base, base, base);
            }
            case 16: return Color.rgb(v, v, v);
            case 17: { int inv = 255 - v; return Color.rgb(inv, inv, inv); }
            case 21: {
                if (motionHeat != null && i < motionHeat.length) {
                    if (motionHeat[i] > 180) return Color.rgb(255, 40, 40);
                    if (motionHeat[i] > 80) return Color.rgb(255, 140, 0);
                }
                return Color.rgb((int)(v*0.22f), v, (int)(v*0.32f));
            }
            case 20: return Color.rgb((int)(v*0.25f), v, (int)(v*0.35f));
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

    private void drawObjectBoxes(Canvas canvas, int w, int h) {
        if (motionHeat == null || motionHeat.length != w * h) return;
        int cols = 8, rows = 6;
        boolean[] active = new boolean[cols * rows];
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int x0 = cx * w / cols, x1 = (cx + 1) * w / cols;
                int y0 = cy * h / rows, y1 = (cy + 1) * h / rows;
                int count = 0;
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (motionHeat[y * w + x] > 160) count++;
                active[cy * cols + cx] = count > (x1 - x0) * (y1 - y0) / 25;
            }
        }
        boolean[] seen = new boolean[cols * rows];
        Paint box = new Paint(); box.setColor(Color.YELLOW); box.setStyle(Paint.Style.STROKE); box.setStrokeWidth(3f);
        Paint text = new Paint(); text.setColor(Color.YELLOW); text.setTextSize(Math.max(13f, w / 24f)); text.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        int obj = 0;
        for (int i = 0; i < active.length; i++) {
            if (!active[i] || seen[i]) continue;
            int minCx = cols, maxCx = -1, minCy = rows, maxCy = -1;
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(i); seen[i] = true;
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                int cx = cur % cols, cy = cur / cols;
                minCx = Math.min(minCx, cx); maxCx = Math.max(maxCx, cx);
                minCy = Math.min(minCy, cy); maxCy = Math.max(maxCy, cy);
                int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : dirs) {
                    int nx = cx + d[0], ny = cy + d[1];
                    if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue;
                    int ni = ny * cols + nx;
                    if (active[ni] && !seen[ni]) { seen[ni] = true; queue.add(ni); }
                }
            }
            obj++;
            float left = minCx * w / (float) cols, top = minCy * h / (float) rows;
            float right = (maxCx + 1) * w / (float) cols, bottom = (maxCy + 1) * h / (float) rows;
            canvas.drawRect(left, top, right, bottom, box);
            canvas.drawText("Object " + obj, left + 4, Math.max(16f, top - 4), text);
        }
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

    @Override protected void onDestroy() {
        if (strobeRunnable != null) handler.removeCallbacks(strobeRunnable);
        stopRecording();
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }
}
