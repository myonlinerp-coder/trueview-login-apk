package com.trueview.login;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String SITE_URL = "https://trueviewaudit.blogspot.com/p/login.html";
    private static final String SITE_DOMAIN = "trueviewaudit.blogspot.com";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_CHOOSER_REQUEST_CODE = 200;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String cameraPhotoPath;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                int result = tts.setLanguage(new Locale("en", "IN"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                if (pendingSpeech != null) {
                    tts.speak(pendingSpeech, TextToSpeech.QUEUE_ADD, null, "trueview_tts");
                    pendingSpeech = null;
                }
            }
        });
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void speak(String text) {
                if (ttsReady && tts != null) {
                    tts.speak(text, TextToSpeech.QUEUE_ADD, null, "trueview_tts");
                } else {
                    pendingSpeech = text;
                }
            }
        }, "AndroidTTS");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setSupportZoom(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.contains(SITE_DOMAIN)) {
                    view.loadUrl(url);
                } else {
                    openExternal(url);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Force-define speechSynthesis / SpeechSynthesisUtterance so the
                // website's speak() calls always route to our native Android TTS -
                // some phones' WebView doesn't implement the Web Speech API at all,
                // so simply overriding an existing speak() isn't enough.
                String shim =
                        "(function(){" +
                        "  window.SpeechSynthesisUtterance = function(text){" +
                        "    this.text = text; this.lang=''; this.rate=1; this.pitch=1; this.volume=1;" +
                        "  };" +
                        "  window.speechSynthesis = window.speechSynthesis || {};" +
                        "  window.speechSynthesis.speak = function(utterance){" +
                        "    try {" +
                        "      var t = (utterance && utterance.text) ? utterance.text : String(utterance);" +
                        "      if (window.AndroidTTS) { window.AndroidTTS.speak(t); }" +
                        "    } catch (e) {}" +
                        "  };" +
                        "  window.speechSynthesis.cancel = window.speechSynthesis.cancel || function(){};" +
                        "  window.speechSynthesis.getVoices = window.speechSynthesis.getVoices || function(){ return []; };" +
                        "})();";
                view.evaluateJavascript(shim, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            // GPS permission prompt from the website (navigator.geolocation)
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                boolean granted = ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }

            // Handles window.open() calls from the website (WhatsApp send, Google Maps
            // directions, photo preview links) - normal WebView silently ignores these,
            // so we catch the target URL with a throwaway WebView and hand it to the
            // matching native app (WhatsApp, Maps, browser) instead.
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView capture = new WebView(MainActivity.this);
                capture.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView captureView, String url) {
                        openExternal(url);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(capture);
                resultMsg.sendToTarget();
                return true;
            }

            // Live camera preview permission from the website (navigator.mediaDevices.getUserMedia)
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    java.util.List<String> granted = new java.util.ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                                && ContextCompat.checkSelfPermission(MainActivity.this,
                                        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            granted.add(resource);
                        } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                                && ContextCompat.checkSelfPermission(MainActivity.this,
                                        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            granted.add(resource);
                        }
                    }
                    if (!granted.isEmpty()) {
                        request.grant(granted.toArray(new String[0]));
                    } else {
                        request.deny();
                    }
                });
            }

            // Custom alert dialog - no website URL shown, just "Alert"
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Alert")
                        .setMessage(message)
                        .setPositiveButton("OK", (dialog, which) -> result.confirm())
                        .setOnCancelListener((DialogInterface dialog) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }

            // Custom confirm dialog - no website URL shown, just "Confirm"
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Confirm")
                        .setMessage(message)
                        .setPositiveButton("OK", (dialog, which) -> result.confirm())
                        .setNegativeButton("Cancel", (dialog, which) -> result.cancel())
                        .setOnCancelListener((DialogInterface dialog) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }

            // Custom prompt dialog (text input) - no website URL shown, just "Input"
            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                    final android.webkit.JsPromptResult result) {
                final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                if (defaultValue != null) {
                    input.setText(defaultValue);
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Input")
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("OK", (dialog, which) ->
                                result.confirm(input.getText().toString()))
                        .setNegativeButton("Cancel", (dialog, which) -> result.cancel())
                        .setOnCancelListener((DialogInterface dialog) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }

            // Camera / file upload trigger from the website (<input type=file capture>)
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCb,
                    FileChooserParams fileChooserParams) {

                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = filePathCb;

                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                File photoFile = null;
                if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                    try {
                        photoFile = createImageFile();
                        takePictureIntent.putExtra("PhotoPath", cameraPhotoPath);
                    } catch (IOException ex) {
                        photoFile = null;
                    }
                    if (photoFile != null) {
                        cameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                        Uri photoUri = FileProvider.getUriForFile(MainActivity.this,
                                "com.trueview.login.fileprovider", photoFile);
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } else {
                        takePictureIntent = null;
                    }
                }

                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentSelectionIntent.setType("image/*");

                Intent[] intentArray;
                if (takePictureIntent != null) {
                    intentArray = new Intent[]{takePictureIntent};
                } else {
                    intentArray = new Intent[0];
                }

                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Select or capture image");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);

                startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        // Without this, WebView has NO built-in download support at all - any
        // <a download> click (blob: or data: URI) or Content-Disposition:
        // attachment response is silently dropped with no callback and no
        // error. This is exactly why the Client Portal's "Save / Share PDF"
        // button did nothing: there was nowhere for that download to go.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            if (url != null && url.startsWith("data:")) {
                // jsPDF-style in-page files (e.g. the Client Portal's PDF
                // report) arrive as a data: URI, not a real network request -
                // DownloadManager only accepts http/https, so decode the
                // base64 payload ourselves and write it straight into
                // Downloads.
                new Thread(() -> {
                    try {
                        saveDataUriToDownloads(url, mimetype, fileName);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
            } else if (url != null) {
                // Normal http(s) attachment - hand off to Android's own
                // Download Manager (handles the notification, retry, etc.).
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setMimeType(mimetype);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        requestNeededPermissions();
    }

    // Decodes a "data:<mime>;base64,<data>" URI (what jsPDF's pdf.output
    // ('datauristring') produces) and writes the bytes straight into the
    // device's Downloads folder. Runs on a background thread (called from
    // setDownloadListener above) since Base64-decoding + writing a multi-MB
    // PDF shouldn't happen on the UI thread.
    private void saveDataUriToDownloads(String dataUri, String mimeType, String fileName) throws IOException {
        int commaIndex = dataUri.indexOf(',');
        if (commaIndex < 0) {
            throw new IOException("Malformed data URI");
        }
        byte[] bytes = Base64.decode(dataUri.substring(commaIndex + 1), Base64.DEFAULT);
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = "application/octet-stream";
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage (Android 10+): write via MediaStore's public
            // Downloads collection - this is the only supported way to
            // place a file directly in Downloads on modern Android.
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            ContentResolver resolver = getContentResolver();
            Uri itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (itemUri == null) {
                throw new IOException("Could not create file in Downloads");
            }
            try (OutputStream out = resolver.openOutputStream(itemUri)) {
                if (out == null) {
                    throw new IOException("Could not open output stream");
                }
                out.write(bytes);
            }
        } else {
            // Pre-scoped-storage devices: WRITE_EXTERNAL_STORAGE (already
            // requested up to SDK 28 in the manifest) lets us write directly.
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(new File(downloadsDir, fileName))) {
                out.write(bytes);
            }
        }
    }

    private void openExternal(String url) {
        if (url == null || url.startsWith("data:")) {
            return; // inline data URIs (e.g. base64 photo preview) - nothing external to open
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Idha open panna app phone-la illa", Toast.LENGTH_SHORT).show();
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "TVA_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST_CODE || filePathCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        Uri[] results = null;

        if (resultCode == Activity.RESULT_OK) {
            if (data == null || data.getDataString() == null) {
                // Photo was taken with the camera
                if (cameraPhotoPath != null) {
                    results = new Uri[]{Uri.parse(cameraPhotoPath)};
                }
            } else {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
        }

        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    private void requestNeededPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.VIBRATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        boolean needsRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }

        if (needsRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            // Already granted from a previous run - load the site right away
            webView.loadUrl(SITE_URL);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                            "Camera / Location permission venum, app properly work aaga",
                            Toast.LENGTH_LONG).show();
                    break;
                }
            }
            // Load the site only after the permission dialog is fully answered,
            // so GPS / camera requests from the page don't race the OS permission prompt
            webView.loadUrl(SITE_URL);
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
