package com.saplingsync;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;
    private static final int FILE_CHOOSER_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " SaplingSync/1.0");

        // Bridge so the web app can save files (e.g. CSV export) to the
        // Downloads folder — a bare WebView can't trigger blob downloads itself.
        webView.addJavascriptInterface(new DownloadBridge(), "AndroidDownloader");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> filePath,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = filePath;

                // Camera intent
                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                File photo = createImageFile();
                if (photo != null) {
                    cameraImageUri = Uri.fromFile(photo);
                    camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
                }

                // Gallery intent (multi-select)
                Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
                gallery.setType("image/*");
                gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                Intent chooser = Intent.createChooser(gallery, "Select Photo");
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                return true;
            }
        });

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requestPermissions(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 1);
        } else {
            requestPermissions(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_MEDIA_IMAGES
            }, 1);
        }

        webView.loadUrl("file:///android_asset/index.html");
    }

    private File createImageFile() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            return File.createTempFile("IMG_" + ts, ".jpg", getExternalFilesDir(null));
        } catch (IOException e) { return null; }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = null;
        if (res == RESULT_OK) {
            if (data == null) {
                if (cameraImageUri != null) results = new Uri[]{cameraImageUri};
            } else {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++)
                        results[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /** Exposed to JS as window.AndroidDownloader. Saves text content to Downloads. */
    private class DownloadBridge {
        @JavascriptInterface
        public void saveText(String content, String filename, String mime) {
            if (filename == null || filename.isEmpty()) filename = "download.txt";
            if (mime == null || mime.isEmpty()) mime = "text/plain";
            try {
                byte[] bytes = content.getBytes("UTF-8");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) { toast("Could not save file"); return; }
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(bytes);
                    os.close();
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream fos = new FileOutputStream(new File(dir, filename));
                    fos.write(bytes);
                    fos.close();
                }
                toast("Saved to Downloads: " + filename);
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
        }
    }
}
