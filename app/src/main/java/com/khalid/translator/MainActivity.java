package com.khalid.translator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int MIC_REQ = 101;
    private static final int FILE_REQ = 102;
    private ValueCallback<Uri[]> filePathCallback;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private PermissionRequest pendingWebRequest;
    private boolean pendingNativeStart = false;

    // Native speech recognition (Android WebView has no Web Speech API)
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private boolean paused = false; // app in background: stop the mic, resume when back
    private String lang = "zh-CN";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // saved sessions + keys
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Serve assets from a secure https origin (required for microphone + clipboard)
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        // Microphone for Azure mode (getUserMedia inside the page)
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                            if (hasMic()) {
                                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                            } else {
                                pendingWebRequest = request;
                                askMic();
                            }
                            return;
                        }
                    }
                    request.deny();
                });
            }

            // lets the page's "import backup" button open the phone's file picker
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "اختر ملف النسخة الاحتياطية"), FILE_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new SpeechBridge(), "AndroidSpeech");

        if (!hasMic()) askMic();
        web.loadUrl(APP_URL);
    }

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askMic() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQ && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != MIC_REQ) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingWebRequest != null) {
            if (granted) pendingWebRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingWebRequest.deny();
            pendingWebRequest = null;
        }
        if (pendingNativeStart) {
            pendingNativeStart = false;
            if (granted) startListening(lang);
            else sendToPage("error", "not-allowed");
        }
    }

    /* ---------------- JS bridge ---------------- */
    private class SpeechBridge {
        @JavascriptInterface
        public void start(String language) {
            runOnUiThread(() -> startListening(language));
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> stopListening());
        }

        // Black-screen mode: drop brightness to minimum and keep the screen awake
        @JavascriptInterface
        public void dim(boolean on) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else if (!listening) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        // Saves the backup file to Downloads. Returns where it was saved, or "" on failure.
        @JavascriptInterface
        public String saveFile(String name, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return "";
                        os.write(bytes);
                    }
                    return "Download/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "";
                    File f = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean available() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }
    }

    private void sendToPage(String type, String text) {
        final String js = "window.__nativeSpeech&&window.__nativeSpeech('" + type + "',"
                + JSONObject.quote(text == null ? "" : text) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /* ---------------- continuous listening ---------------- */
    private void startListening(String language) {
        if (language != null && !language.isEmpty()) lang = language;
        if (!hasMic()) {
            pendingNativeStart = true;
            askMic();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendToPage("error", "unavailable");
            return;
        }
        listening = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        listenOnce();
    }

    private void listenOnce() {
        if (!listening || paused || recognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        muteBeep();
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            handler.postDelayed(this::listenOnce, 500);
        }
    }

    private void restartSoon(long delay) {
        if (!listening || paused) return;
        handler.postDelayed(this::listenOnce, delay);
    }

    private void stopListening() {
        listening = false;
        paused = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
        unmute.run();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // The recognizer plays a short beep each time it restarts; mute it so the app stays silent.
    private void muteBeep() {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);
        } catch (Exception ignored) { }
        handler.removeCallbacks(unmute);
        handler.postDelayed(unmute, 900);
    }

    private final Runnable unmute = () -> {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) { }
    };

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partial) {
            ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) sendToPage("partial", r.get(0));
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) sendToPage("final", r.get(0));
            restartSoon(100);
        }

        @Override
        public void onError(int error) {
            if (!listening) return;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stopListening();
                    sendToPage("error", "not-allowed");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    sendToPage("error", "network");
                    restartSoon(1500);
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    try { recognizer.cancel(); } catch (Exception ignored) { }
                    restartSoon(400);
                    break;
                default: // no match, speech timeout, client error: just keep listening
                    restartSoon(150);
            }
        }
    };

    @Override
    protected void onStop() {
        super.onStop();
        if (listening) {
            paused = true;
            handler.removeCallbacksAndMessages(null);
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Exception ignored) { }
            }
            unmute.run();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (paused) {
            paused = false;
            if (listening) handler.postDelayed(this::listenOnce, 300);
        }
    }

    @Override
    protected void onDestroy() {
        stopListening();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
