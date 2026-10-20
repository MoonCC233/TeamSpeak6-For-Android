package dev.tsdroid.pad;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * B2s Pad shell: a thin, landscape-first WebView host for a self-hosted
 * WebSpeak instance.
 *
 * <p>The shell deliberately keeps the web UI untouched. It only owns the
 * Android concerns the browser cannot provide on its own: a persistent WebView,
 * microphone permission bridging, immersive fullscreen, file upload, HTTPS
 * error handling, and the future native screen-share publishing bridge.</p>
 *
 * <p>License: AGPL-3.0-only, matching the vendored {@code pad/} upstream.</p>
 */
public final class MainActivity extends Activity {
    private static final String TAG = "TS6Pad";
    private static final String PREFS = "ts6pad_shell";
    private static final String PREF_URL = "gateway_url";
    private static final String PREF_INSECURE = "allow_insecure_tls";
    private static final String PREF_SAFE_AREA = "safe_area_insets";
    private static final String DEFAULT_URL = "https://shanghai.webspeak.online";
    private static final int REQ_MIC = 4101;
    private static final int REQ_FILE = 4102;

    /**
     * Injected after the page loads. It wraps getUserMedia so the shell can
     * learn when a microphone/voice stream becomes live or ends, which drives
     * the foreground service. The wrapper is intentionally defensive: the web
     * app must keep working even if any of this fails.
     */
    private static final String INJECT_VOICE_HOOK = """
            (function(){
              try {
                console.log('[TS6Pad] bridge=' + (!!window.TS6PadNative)
                  + ' version=' + (window.TS6PadNative ? window.TS6PadNative.getVersion() : 'n/a')
                  + ' ua=' + navigator.userAgent);
                if (window.__ts6padVoiceHookInstalled) { return; }
                window.__ts6padVoiceHookInstalled = true;
                var bridge = window.TS6PadNative;
                if (!bridge || typeof bridge.setVoiceActive !== 'function') { return; }
                var notify = function(active) {
                  try { bridge.setVoiceActive(!!active); } catch (e) {}
                };
                var isLive = function(stream) {
                  try {
                    var tracks = stream.getAudioTracks ? stream.getAudioTracks() : [];
                    for (var i = 0; i < tracks.length; i++) {
                      if (tracks[i].readyState === 'live') { return true; }
                    }
                  } catch (e) {}
                  return false;
                };
                var activeStreams = [];
                var watch = function(stream) {
                  if (activeStreams.indexOf(stream) >= 0) { return; }
                  activeStreams.push(stream);
                  notify(true);
                  var timer = setInterval(function() {
                    if (!isLive(stream)) {
                      clearInterval(timer);
                      var index = activeStreams.indexOf(stream);
                      if (index >= 0) { activeStreams.splice(index, 1); }
                      if (activeStreams.length === 0) { notify(false); }
                    }
                  }, 1000);
                };
                if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                  var original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
                  navigator.mediaDevices.getUserMedia = function(constraints) {
                    return original(constraints).then(function(stream) {
                      var hasAudio = false;
                      try { hasAudio = !!(stream.getAudioTracks && stream.getAudioTracks().length); } catch (e) {}
                      if (hasAudio) { watch(stream); }
                      return stream;
                    });
                  };
                }
                window.addEventListener('pagehide', function() { notify(false); });
              } catch (e) {}
            })();
            """;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private ImageButton settingsButton;
    private View errorView;
    private TextView errorDetail;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private SharedPreferences prefs;
    private String gatewayUrl;
    private PermissionRequest pendingPermissionRequest;
    private ValueCallback<Uri[]> pendingFileCallback;
    private SslErrorHandler pendingSslHandler;
    private long lastBackPressedAt;

    private volatile boolean voiceActive;
    private boolean activityResumed;
    private VoiceSessionService.FocusListener focusListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        voiceActive = false;
        focusListener = (hasFocus, transientLoss) -> runOnUiThread(
                () -> onAudioFocusChanged(hasFocus, transientLoss));
        VoiceSessionService.setFocusListener(focusListener);
        configureWindow();
        buildUi();
        configureWebView();

        String savedUrl = prefs.getString(PREF_URL, null);
        if (savedUrl == null || savedUrl.trim().isEmpty()) {
            showGatewayDialog(true);
        } else {
            loadGateway(savedUrl);
        }

        enterImmersive();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.pad_accent)));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33000000));
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progressBar, progressParams);

        buildErrorView();
        buildSettingsButton();

        setContentView(root);
        installSafeAreaInsets();
    }

    private void installSafeAreaInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = 0;
            int top = 0;
            int right = 0;
            int bottom = 0;
            if (prefs.getBoolean(PREF_SAFE_AREA, true)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
                    left = cutout.left;
                    top = cutout.top;
                    right = cutout.right;
                    bottom = cutout.bottom;
                } else {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        left = cutout.getSafeInsetLeft();
                        top = cutout.getSafeInsetTop();
                        right = cutout.getSafeInsetRight();
                        bottom = cutout.getSafeInsetBottom();
                    }
                }
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void buildErrorView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dp(32), dp(32), dp(32), dp(32));
        container.setBackgroundColor(0xF20B1120);
        container.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText(R.string.error_title);
        title.setTextColor(getColor(R.color.pad_text));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);

        TextView detail = new TextView(this);
        detail.setText(R.string.error_message);
        detail.setTextColor(getColor(R.color.pad_text_muted));
        detail.setTextSize(14);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(12), 0, dp(20));

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setTextColor(getColor(R.color.pad_text));
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            if (gatewayUrl != null) {
                loadGateway(gatewayUrl);
            }
        });

        container.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(retry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errorView = container;
        errorDetail = detail;
    }

    private void buildSettingsButton() {
        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(android.R.drawable.ic_menu_manage);
        settingsButton.setColorFilter(Color.WHITE);
        settingsButton.setContentDescription(getString(R.string.settings));
        settingsButton.setAlpha(0.6f);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x66000000);
        settingsButton.setBackground(background);

        settingsButton.setOnClickListener(v -> showShellMenu());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.TOP | Gravity.END);
        params.topMargin = dp(14);
        params.rightMargin = dp(14);
        root.addView(settingsButton, params);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSaveFormData(false);
        settings.setGeolocationEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " TS6Pad/0.1");
        settings.setSafeBrowsingEnabled(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        // B2s only exposes harmless capability probes. B5 will add the native
        // MediaProjection publisher behind explicit origin checks.
        webView.addJavascriptInterface(new NativeBridge(), "TS6PadNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageStarted(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                Log.i(TAG, "Page finished: " + url);
                view.evaluateJavascript(INJECT_VOICE_HOOK, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageFailed(request.getUrl().toString(), error.getDescription().toString());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (prefs.getBoolean(PREF_INSECURE, false)) {
                    Log.w(TAG, "Proceeding with self-signed certificate for " + error.getUrl());
                    handler.proceed();
                    return;
                }
                showSslErrorDialog(handler, error);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                handlePermissionRequest(request);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingPermissionRequest == request) {
                    pendingPermissionRequest = null;
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                return openFileChooser(filePathCallback);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, "console." + message.messageLevel() + ": " + message.message()
                        + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showCustomView(view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                openExternal(Uri.parse(url));
            } else {
                Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void pageStarted(String url) {
        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        Log.i(TAG, "Page started: " + url);
    }

    private void pageFailed(String url, String description) {
        progressBar.setVisibility(View.GONE);
        StringBuilder message = new StringBuilder(getString(R.string.error_message));
        message.append("\n\n").append(description);
        if (url != null && !url.isEmpty()) {
            message.append("\n").append(url);
        }
        errorDetail.setText(message.toString());
        errorView.setVisibility(View.VISIBLE);
        Log.e(TAG, "Page failed: " + url + " / " + description);
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (isSameGateway(uri)) {
                return false;
            }
            openExternal(uri);
            return true;
        }
        if ("mailto".equalsIgnoreCase(scheme)
                || "tel".equalsIgnoreCase(scheme)
                || "sms".equalsIgnoreCase(scheme)) {
            openExternal(uri);
            return true;
        }
        // Let the WebView handle about:, blob:, data:, javascript:, etc.
        return false;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.open_external_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isSameGateway(Uri uri) {
        if (gatewayUrl == null) {
            return false;
        }
        Uri gateway = Uri.parse(gatewayUrl);
        String host = uri.getHost();
        String gatewayHost = gateway.getHost();
        if (host == null || gatewayHost == null || !host.equalsIgnoreCase(gatewayHost)) {
            return false;
        }
        return effectivePort(uri) == effectivePort(gateway);
    }

    private boolean isTrustedOrigin(Uri origin) {
        if (origin == null || gatewayUrl == null) {
            return false;
        }
        Uri gateway = Uri.parse(gatewayUrl);
        String host = origin.getHost();
        String gatewayHost = gateway.getHost();
        if (host == null || gatewayHost == null || !host.equalsIgnoreCase(gatewayHost)) {
            return false;
        }
        boolean sameScheme = String.valueOf(origin.getScheme())
                .equalsIgnoreCase(String.valueOf(gateway.getScheme()));
        return sameScheme && effectivePort(origin) == effectivePort(gateway);
    }

    private static int effectivePort(Uri uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void handlePermissionRequest(PermissionRequest request) {
        if (!isTrustedOrigin(request.getOrigin())) {
            Log.w(TAG, "Denied WebView permission for untrusted origin " + request.getOrigin());
            request.deny();
            return;
        }

        boolean wantsAudio = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                wantsAudio = true;
                break;
            }
        }
        if (!wantsAudio) {
            request.deny();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            return;
        }

        if (pendingPermissionRequest != null) {
            pendingPermissionRequest.deny();
        }
        pendingPermissionRequest = request;
        requestPermissions(microphonePermissions(), REQ_MIC);
    }

    private String[] microphonePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
            };
        }
        return new String[]{Manifest.permission.RECORD_AUDIO};
    }

    private boolean openFileChooser(ValueCallback<Uri[]> filePathCallback) {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
        }
        pendingFileCallback = filePathCallback;

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_file)), REQ_FILE);
            return true;
        } catch (ActivityNotFoundException exception) {
            pendingFileCallback = null;
            Toast.makeText(this, R.string.file_chooser_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenContainer != null) {
            callback.onCustomViewHidden();
            return;
        }
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }

        fullscreenCallback = callback;
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setVisibility(View.INVISIBLE);
        settingsButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        enterImmersive();
    }

    private void hideCustomView() {
        if (fullscreenContainer == null) {
            return;
        }
        fullscreenContainer.removeAllViews();
        root.removeView(fullscreenContainer);
        fullscreenContainer = null;
        webView.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        if (callback != null) {
            callback.onCustomViewHidden();
        }
        enterImmersive();
    }

    private void showGatewayDialog(boolean firstRun) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.gateway_hint);
        input.setText(prefs.getString(PREF_URL, DEFAULT_URL));
        input.setTextColor(getColor(R.color.pad_text));
        input.setHintTextColor(getColor(R.color.pad_text_muted));
        content.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CheckBox allowInsecure = new CheckBox(this);
        allowInsecure.setText(R.string.allow_insecure);
        allowInsecure.setTextColor(getColor(R.color.pad_text));
        allowInsecure.setChecked(prefs.getBoolean(PREF_INSECURE, false));
        content.addView(allowInsecure, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText(R.string.gateway_help);
        help.setTextColor(getColor(R.color.pad_text_muted));
        help.setTextSize(12);
        help.setPadding(0, dp(8), 0, 0);
        content.addView(help, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.gateway_title)
                .setView(scroll)
                .setPositiveButton(R.string.connect, null)
                .setNegativeButton(firstRun ? R.string.exit : R.string.cancel, (d, which) -> {
                    if (firstRun) {
                        finish();
                    }
                })
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(!firstRun);
        dialog.setOnShowListener(d -> {
            Button connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            connect.setOnClickListener(v -> {
                String normalized = normalizeUrl(input.getText().toString());
                if (normalized == null) {
                    input.setError(getString(R.string.invalid_url));
                    return;
                }
                prefs.edit()
                        .putString(PREF_URL, normalized)
                        .putBoolean(PREF_INSECURE, allowInsecure.isChecked())
                        .apply();
                dialog.dismiss();
                loadGateway(normalized);
            });
        });

        dialog.show();
        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
    }

    private String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.contains(" ")) {
            return null;
        }
        if (!value.contains("://")) {
            String lower = value.toLowerCase(Locale.ROOT);
            boolean local = lower.startsWith("localhost")
                    || lower.startsWith("127.")
                    || lower.startsWith("10.")
                    || lower.startsWith("192.168.")
                    || isPrivate172(lower);
            value = (local ? "http://" : "https://") + value;
        }
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return null;
        }
        return uri.toString();
    }

    private static boolean isPrivate172(String value) {
        if (!value.startsWith("172.")) {
            return false;
        }
        int dot = value.indexOf('.', 4);
        if (dot < 0) {
            return false;
        }
        try {
            int second = Integer.parseInt(value.substring(4, dot));
            return second >= 16 && second <= 31;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void loadGateway(String url) {
        gatewayUrl = url;
        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
        Log.i(TAG, "Loading gateway: " + url);
    }

    private void showShellMenu() {
        boolean safeArea = prefs.getBoolean(PREF_SAFE_AREA, true);
        String[] items = {
                getString(R.string.reload),
                getString(R.string.change_gateway),
                getString(safeArea ? R.string.safe_area_on : R.string.safe_area_off),
                getString(R.string.clear_data),
                getString(R.string.about),
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.settings)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            if (gatewayUrl != null) {
                                loadGateway(gatewayUrl);
                            }
                            break;
                        case 1:
                            showGatewayDialog(false);
                            break;
                        case 2:
                            prefs.edit().putBoolean(PREF_SAFE_AREA, !safeArea).apply();
                            root.requestApplyInsets();
                            break;
                        case 3:
                            confirmClearData();
                            break;
                        case 4:
                            showAbout();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.clear_confirm_title)
                .setMessage(R.string.clear_confirm_message)
                .setPositiveButton(R.string.clear, (dialog, which) -> clearWebData())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void clearWebData() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
        WebStorage.getInstance().deleteAllData();
        webView.clearCache(true);
        webView.clearFormData();
        webView.clearHistory();
        webView.clearSslPreferences();
        if (gatewayUrl != null) {
            loadGateway(gatewayUrl);
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.about)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSslErrorDialog(SslErrorHandler handler, SslError error) {
        if (pendingSslHandler != null) {
            handler.cancel();
            return;
        }
        pendingSslHandler = handler;
        String detail = error.getUrl() + "\n\nSSL error: " + error.getPrimaryError();
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.ssl_title)
                .setMessage(getString(R.string.ssl_message, detail))
                .setPositiveButton(R.string.ssl_continue, (d, which) -> {
                    pendingSslHandler = null;
                    handler.proceed();
                })
                .setNegativeButton(R.string.cancel, (d, which) -> {
                    pendingSslHandler = null;
                    handler.cancel();
                })
                .setOnCancelListener(d -> {
                    pendingSslHandler = null;
                    handler.cancel();
                })
                .create();
        dialog.show();
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreenContainer == null) {
            enterImmersive();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        enterImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (webView != null) {
            webView.onResume();
        }
        if (voiceActive) {
            startVoiceService();
        }
        enterImmersive();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (webView != null) {
            if (voiceActive) {
                if (!startVoiceService()) {
                    webView.onPause();
                }
            } else {
                webView.onPause();
            }
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenContainer != null) {
            hideCustomView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < 2000) {
            super.onBackPressed();
        } else {
            lastBackPressedAt = now;
            Toast.makeText(this, R.string.back_exit, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) {
            return;
        }
        PermissionRequest pending = pendingPermissionRequest;
        pendingPermissionRequest = null;
        if (pending == null) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pending.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pending.deny();
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) {
            return;
        }
        if (pendingFileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        voiceActive = false;
        stopVoiceService();
        if (focusListener != null) {
            VoiceSessionService.clearFocusListener(focusListener);
            focusListener = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean startVoiceService() {
        try {
            Intent intent = new Intent(this, VoiceSessionService.class);
            intent.setAction(VoiceSessionService.ACTION_START);
            startForegroundService(intent);
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to start voice foreground service", exception);
            return false;
        }
    }

    private void stopVoiceService() {
        stopService(new Intent(this, VoiceSessionService.class));
    }

    private void updateVoiceActive(boolean active) {
        if (active) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Voice hook reported active without RECORD_AUDIO");
                return;
            }
            voiceActive = true;
            startVoiceService();
        } else {
            if (!voiceActive) {
                return;
            }
            voiceActive = false;
            stopVoiceService();
            if (!activityResumed && webView != null) {
                webView.onPause();
            }
        }
    }

    private void onAudioFocusChanged(boolean hasFocus, boolean transientLoss) {
        Log.i(TAG, "Audio focus mirror: hasFocus=" + hasFocus + " transient=" + transientLoss);
        if (webView == null) {
            return;
        }
        String state = hasFocus ? "gain" : (transientLoss ? "transient-loss" : "loss");
        webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('ts6pad:audiofocus',{detail:{state:'"
                        + state + "'}}));",
                null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Small, deliberately harmless capability probe for the B2s spike. B5 will
     * extend this object with MediaProjection/WebRTC methods and enforce the
     * gateway origin before every call.
     */
    public final class NativeBridge {
        @JavascriptInterface
        public String getVersion() {
            return "0.2.0-pad-b3";
        }

        @JavascriptInterface
        public String getPlatform() {
            return "android-pad-shell";
        }

        @JavascriptInterface
        public boolean isScreenShareAvailable() {
            return false;
        }

        @JavascriptInterface
        public String getGatewayUrl() {
            return gatewayUrl == null ? "" : gatewayUrl;
        }

        @JavascriptInterface
        public void setVoiceActive(boolean active) {
            runOnUiThread(() -> updateVoiceActive(active));
        }
    }
}
