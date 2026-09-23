package com.fnnas.photouploader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全屏原生扫码（体验同微信/QQ）：CameraX 取实时帧 → 原生 ZXing 解码。
 * 不依赖 Google Play / GMS，国内车间手机（华为/小米/OPPO/vivo 等）通用。
 * 解码命中即震动并回传结果，未授权/无相机则回传 null 让 Web 端回退。
 */
public class BarcodeScannerActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ScanOverlay overlay;
    private Camera camera;
    private MultiFormatReader reader;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean done = new AtomicBoolean(false);
    private boolean torchOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(previewView);

        overlay = new ScanOverlay(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlay);

        // 顶栏：关闭 / 标题 / 补光
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(44), dp(16), dp(12));
        top.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(this);
        close.setText("✕ 关闭");
        close.setTextColor(Color.WHITE);
        close.setTextSize(16);
        close.setPadding(dp(8), dp(8), dp(8), dp(8));
        close.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        TextView title = new TextView(this);
        title.setText("对准条码，自动识别");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView flash = new TextView(this);
        flash.setText("💡 补光");
        flash.setTextColor(Color.WHITE);
        flash.setTextSize(16);
        flash.setPadding(dp(8), dp(8), dp(8), dp(8));
        flash.setAlpha(0.55f);
        flash.setOnClickListener(v -> toggleTorch(flash));

        top.addView(close);
        top.addView(title);
        top.addView(flash);
        root.addView(top);

        // 底部提示
        TextView hint = new TextView(this);
        hint.setText("将条码放入框内，自动识别");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;
        hp.bottomMargin = dp(48);
        hint.setLayoutParams(hp);
        root.addView(hint);

        setContentView(root);

        setupReader();
        overlay.start();
        startCamera();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void setupReader() {
        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> fmts = new ArrayList<>();
        fmts.add(BarcodeFormat.CODE_128);
        fmts.add(BarcodeFormat.CODE_39);
        fmts.add(BarcodeFormat.CODE_93);
        fmts.add(BarcodeFormat.CODABAR);
        fmts.add(BarcodeFormat.ITF);
        fmts.add(BarcodeFormat.EAN_13);
        fmts.add(BarcodeFormat.EAN_8);
        fmts.add(BarcodeFormat.UPC_A);
        fmts.add(BarcodeFormat.UPC_E);
        fmts.add(BarcodeFormat.QR_CODE);
        fmts.add(BarcodeFormat.DATA_MATRIX);
        fmts.add(BarcodeFormat.AZTEC);
        fmts.add(BarcodeFormat.PDF_417);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, fmts);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        reader.setHints(hints);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindCamera(future.get());
            } catch (Exception e) {
                finishWithError();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider provider) {
        try {
            provider.unbindAll();
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            final String[] found = {null};
            analysis.setAnalyzer(analysisExecutor, image -> {
                if (busy.get()) { image.close(); return; }
                busy.set(true);
                try {
                    Bitmap bmp = image.toBitmap();
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    bmp.recycle();
                    RGBLuminanceSource src = new RGBLuminanceSource(w, h, px);
                    BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
                    try {
                        Result r = reader.decodeWithState(bb);
                        if (r != null && r.getText() != null && !r.getText().trim().isEmpty()) {
                            found[0] = r.getText().trim();
                        }
                    } catch (NotFoundException ignore) {
                        // 本帧无条码，继续
                    }
                } catch (Exception ignore) {
                    // 取帧/解码异常忽略，下一帧再试
                } finally {
                    image.close();
                    busy.set(false);
                }
                if (found[0] != null) {
                    onDecoded(found[0]);
                }
            });

            CameraSelector selector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
            camera = provider.bindToLifecycle(this, selector, preview, analysis);
        } catch (Exception e) {
            finishWithError();
        }
    }

    private void toggleTorch(TextView flash) {
        if (camera == null) return;
        torchOn = !torchOn;
        try {
            camera.getCameraControl().enableTorch(torchOn);
            flash.setAlpha(torchOn ? 1f : 0.55f);
        } catch (Exception ignore) {
        }
    }

    private void onDecoded(final String code) {
        if (done.getAndSet(true)) return;
        runOnUiThread(() -> {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(60);
                    }
                }
            } catch (Exception ignore) {
            }
            String cbId = getIntent().getStringExtra("cbId");
            BarcodeScannerBridge.deliver(cbId, code);
            setResult(RESULT_OK);
            finish();
        });
    }

    private void finishWithError() {
        if (done.getAndSet(true)) return;
        runOnUiThread(() -> {
            String cbId = getIntent().getStringExtra("cbId");
            BarcodeScannerBridge.deliver(cbId, null);
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (overlay != null) overlay.stop();
        analysisExecutor.shutdown();
    }

    // 取景框遮罩 + 扫描线（纯代码绘制，无需任何 res 资源）
    private static class ScanOverlay extends View {
        private final Paint maskPaint = new Paint();
        private final Paint linePaint = new Paint();
        private final Paint cornerPaint = new Paint();
        private RectF win;
        private float lineY = 0f;
        private android.animation.ValueAnimator anim;

        public ScanOverlay(Context ctx) {
            super(ctx);
            maskPaint.setColor(Color.parseColor("#88000000"));
            linePaint.setColor(Color.parseColor("#4ADE80"));
            linePaint.setStrokeWidth(3f);
            cornerPaint.setColor(Color.parseColor("#4ADE80"));
            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setStrokeWidth(4f);
        }

        void start() {
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1800);
            anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            anim.addUpdateListener(a -> {
                lineY = (Float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            float w = r - l, h = b - t;
            float ww = Math.min(w * 0.78f, 720f);
            float wh = Math.min(ww * 0.5f, h * 0.5f);
            float cx = w / 2f, cy = h * 0.42f;
            win = new RectF(cx - ww / 2f, cy - wh / 2f, cx + ww / 2f, cy + wh / 2f);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (win == null) return;
            int W = getWidth(), H = getHeight();
            c.drawRect(0, 0, W, win.top, maskPaint);
            c.drawRect(0, win.bottom, W, H, maskPaint);
            c.drawRect(0, win.top, win.left, win.bottom, maskPaint);
            c.drawRect(win.right, win.top, W, win.bottom, maskPaint);
            float len = Math.min(win.width(), win.height()) * 0.18f;
            c.drawLine(win.left, win.top, win.left + len, win.top, cornerPaint);
            c.drawLine(win.left, win.top, win.left, win.top + len, cornerPaint);
            c.drawLine(win.right - len, win.top, win.right, win.top, cornerPaint);
            c.drawLine(win.right, win.top, win.right, win.top + len, cornerPaint);
            c.drawLine(win.left, win.bottom - len, win.left, win.bottom, cornerPaint);
            c.drawLine(win.left, win.bottom, win.left + len, win.bottom, cornerPaint);
            c.drawLine(win.right - len, win.bottom, win.right, win.bottom, cornerPaint);
            c.drawLine(win.right, win.bottom - len, win.right, win.bottom, cornerPaint);
            float y = win.top + lineY * win.height();
            c.drawLine(win.left, y, win.right, y, linePaint);
        }
    }
}
