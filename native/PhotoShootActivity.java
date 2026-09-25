package com.fnnas.photouploader;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v4.8：全屏原生拍照页（连拍、拍完不退出、全程没有系统相机的「确定」步骤）。
 *
 * 为什么必须有它：
 *   Capacitor 的插件桥（window.Capacitor）只注入到本地源页面（https://localhost），
 *   WebViewLocalServer 对远程 http://NAS:3080 页面匹配不到 handler → 不注入任何插件 JS。
 *   于是上传页里 CameraPreview / Camera 插件全部不存在，拍照只能靠 <input type=file>
 *   唤起系统相机，而系统相机自带「确认」页 —— 这就是「拍完免按确定」一直实现不了的根因。
 *   addJavascriptInterface 注入的对象则对任何页面都可用（原生扫码桥已验证），
 *   因此这里走同样的思路：JS 调 PhotoShootNative._shoot() 拉起本页，
 *   每拍一张立即分块回传 base64 给页面，页面复用既有水印/落库/上传管线。
 *
 * 交互：进入即实时取景 → 点 ⭕ 拍一张（可连拍）→ 点「完成」返回上传页。
 * 任一步失败都只是回退到原系统相机链路，不影响既有功能。
 */
public class PhotoShootActivity extends AppCompatActivity {

    private static final int JPEG_QUALITY = 88;
    private static final int CHUNK = 100000; // 单次 evaluateJavascript 字符数（Binder 事务上限约 1MB）
    // v4.9.6：出图分辨率 1920×1440 → 1600×1200。
    // 老机型（荣耀 Play / 4GB）在出图瞬间会同时存在「原图 + 旋转后」两张 bitmap（≈22MB），
    // 加上 base64 回传与页面解码，容易被系统杀进程（表现为点快门闪退）。
    // 1600×1200（192 万像素）与网页端归档尺寸一致，车间归档/水印完全够用。
    private static final int OUT_W = 1600;
    private static final int OUT_H = 1200;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private ExecutorService camExec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String cbId = "";
    private int shotCount = 0;
    private TextView countText;
    private TextView shootBtn;
    private boolean torchOn = false;
    private volatile boolean shooting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cbId = getIntent().getStringExtra("cbId");
        if (cbId == null) cbId = "";
        final String title = getIntent().getStringExtra("title");

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(previewView);

        // ---- 顶栏：完成 / 追溯码 / 补光 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(40), dp(16), dp(12));
        top.setBackgroundColor(Color.parseColor("#66000000"));
        top.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView done = new TextView(this);
        done.setText("✕ 完成");
        done.setTextColor(Color.WHITE);
        done.setTextSize(16);
        done.setPadding(dp(8), dp(8), dp(12), dp(8));
        done.setOnClickListener(v -> finishShoot());

        TextView code = new TextView(this);
        String t = (title == null || title.isEmpty()) ? "拍照" : title;
        code.setText(t.length() > 26 ? t.substring(0, 26) + "…" : t);
        code.setTextColor(Color.WHITE);
        code.setTextSize(13);
        code.setGravity(Gravity.CENTER);
        code.setSingleLine(true);
        code.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView flash = new TextView(this);
        flash.setText("💡 补光");
        flash.setTextColor(Color.WHITE);
        flash.setTextSize(16);
        flash.setPadding(dp(12), dp(8), dp(8), dp(8));
        flash.setAlpha(0.55f);
        flash.setOnClickListener(v -> toggleTorch(flash));

        top.addView(done);
        top.addView(code);
        top.addView(flash);
        root.addView(top);

        // ---- 底栏：快门 + 计数提示 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM;
        bottom.setLayoutParams(bp);
        bottom.setPadding(0, dp(16), 0, dp(40));
        bottom.setBackgroundColor(Color.parseColor("#66000000"));

        shootBtn = new TextView(this);
        shootBtn.setText("⭕");
        shootBtn.setTextSize(34);
        shootBtn.setGravity(Gravity.CENTER);
        int d = dp(84);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(d, d);
        sp.bottomMargin = dp(10);
        shootBtn.setLayoutParams(sp);
        shootBtn.setBackgroundDrawable(makeRing());
        shootBtn.setOnClickListener(v -> takeShot());

        countText = new TextView(this);
        countText.setText("已拍 0 张 · 拍完点「完成」返回");
        countText.setTextColor(Color.WHITE);
        countText.setTextSize(13);
        countText.setGravity(Gravity.CENTER);

        bottom.addView(shootBtn);
        bottom.addView(countText);
        root.addView(bottom);

        setContentView(root);
        startCamera();
    }

    private android.graphics.drawable.Drawable makeRing() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Color.parseColor("#22FFFFFF"));
        g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                // v4.9.6：先按目标分辨率绑定；该组合本机不支持时（CameraX 在个别机型会抛异常），
                // 回退到「不指定分辨率」再绑一次，避免直接失败/崩溃 → 仍可拍照
                try {
                    imageCapture = buildImageCapture(true);
                    camera = provider.bindToLifecycle(this, selector, preview, imageCapture);
                } catch (Throwable t) {
                    imageCapture = buildImageCapture(false);
                    camera = provider.bindToLifecycle(this, selector, preview, imageCapture);
                }
                // 连续自动对焦：与扫码页一致，避免近距发虚
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            MeteringPoint pt = previewView.getMeteringPointFactory().createPoint(0.5f, 0.5f);
                            FocusMeteringAction a = new FocusMeteringAction.Builder(pt,
                                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                                    .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
                                    .build();
                            if (camera != null) camera.getCameraControl().startFocusAndMetering(a);
                        } catch (Throwable ignore) {}
                        if (!isFinishing()) ui.postDelayed(this, 2500);
                    }
                }, 700);
            } catch (Exception e) {
                // 相机打不开：立刻结束，JS 会自动回退到系统相机链路
                PhotoShootBridge.deliver(cbId, null);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private ImageCapture buildImageCapture(boolean withTargetResolution) {
        ImageCapture.Builder b = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(JPEG_QUALITY);
        if (withTargetResolution) b.setTargetResolution(new Size(OUT_W, OUT_H));
        return b.build();
    }

    private void takeShot() {
        if (imageCapture == null || shooting) return;
        shooting = true;
        try { shootBtn.setAlpha(0.5f); } catch (Throwable ignore) {}
        imageCapture.takePicture(camExec, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@androidx.annotation.NonNull ImageProxy image) {
                String b64 = null;
                try {
                    Bitmap bmp = image.toBitmap();
                    int rot = image.getImageInfo().getRotationDegrees();
                    if (rot != 0 && bmp != null) {
                        Matrix m = new Matrix();
                        m.postRotate(rot);
                        Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                        if (r != bmp) bmp.recycle();
                        bmp = r;
                    }
                    if (bmp != null) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
                        bmp.recycle();
                        b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    }
                } catch (Throwable ignore) {
                    b64 = null;
                    // v4.9.6：内存不足时不要再留在拍照页反复尝试（老机型会连环崩），直接退出让页面回退系统相机
                    if (ignore instanceof OutOfMemoryError) {
                        runOnUiThread(() -> { PhotoShootBridge.deliver(cbId, null); finish(); });
                    }
                } finally {
                    try { image.close(); } catch (Throwable ignore) {}
                }
                final String result = b64;
                runOnUiThread(() -> {
                    shooting = false;
                    try { shootBtn.setAlpha(1f); } catch (Throwable ignore) {}
                    if (result != null && result.length() > 0) {
                        shotCount++;
                        countText.setText("已拍 " + shotCount + " 张 · 可继续拍，完成点「完成」");
                        PhotoShootBridge.deliver(cbId, result);
                    } else {
                        PhotoShootBridge.deliver(cbId, null); // 通知页面本张失败（页面会回退系统相机）
                    }
                });
            }

            @Override
            public void onError(@androidx.annotation.NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    shooting = false;
                    try { shootBtn.setAlpha(1f); } catch (Throwable ignore) {}
                    PhotoShootBridge.deliver(cbId, null);
                });
            }
        });
    }

    private void toggleTorch(TextView flash) {
        try {
            if (camera == null) return;
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            flash.setAlpha(torchOn ? 1f : 0.55f);
        } catch (Throwable ignore) {}
    }

    private void finishShoot() {
        PhotoShootBridge.deliver(cbId, null);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishShoot();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignore) {}
        try { camExec.shutdown(); } catch (Throwable ignore) {}
    }
}
