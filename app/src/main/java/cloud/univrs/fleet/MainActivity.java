package cloud.univrs.fleet;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
	private static final String HOST = "fleet.univrs.cloud";
	private static final String ORIGIN = "https://" + HOST;
	private static final String BRIDGE_NAME = "univrsFleet";

	private WebView webView;
	private BiometricBridge biometricBridge;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
		boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
		WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
		insetsController.setAppearanceLightStatusBars(!isNight);
		insetsController.setAppearanceLightNavigationBars(!isNight);

		FrameLayout container = new FrameLayout(this);
		SwipeRefreshLayout swipeRefresh = new SwipeRefreshLayout(this);
		webView = new WebView(this);
		swipeRefresh.addView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		swipeRefresh.setOnRefreshListener(() -> webView.reload());
		container.addView(swipeRefresh, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		setContentView(container);
		ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
			Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
			view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
			return WindowInsetsCompat.CONSUMED;
		});

		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		CookieManager.getInstance().setAcceptCookie(true);
		webView.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				Uri url = request.getUrl();
				if (isFleet(url)) {
					return false;
				}
				openExternally(url);
				return true;
			}

			@Override
			public void onPageFinished(WebView view, String url) {
				swipeRefresh.setRefreshing(false);
			}
		});

		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
			biometricBridge = new BiometricBridge(this);
			WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, Collections.singleton(ORIGIN), (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
				if (isMainFrame) {
					biometricBridge.handle(message.getData(), replyProxy);
				}
			});
		}

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (webView.canGoBack()) {
					webView.goBack();
					return;
				}
				setEnabled(false);
				getOnBackPressedDispatcher().onBackPressed();
				setEnabled(true);
			}
		});

		if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
			webView.loadUrl(launchUrl(getIntent()));
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		Uri url = intent.getData();
		if (url != null && isFleet(url)) {
			webView.loadUrl(url.toString());
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		webView.saveState(outState);
	}

	@Override
	protected void onPause() {
		super.onPause();
		CookieManager.getInstance().flush();
	}

	@Override
	protected void onDestroy() {
		webView.destroy();
		super.onDestroy();
	}

	private static boolean isFleet(Uri url) {
		String scheme = url.getScheme();
		String host = url.getHost();
		return scheme != null && host != null
			&& scheme.toLowerCase(Locale.ROOT).equals("https")
			&& host.toLowerCase(Locale.ROOT).equals(HOST);
	}

	private static String launchUrl(Intent intent) {
		Uri url = intent.getData();
		if (url != null && isFleet(url)) {
			return url.toString();
		}
		return ORIGIN + "/";
	}

	private void openExternally(Uri url) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, url));
		} catch (ActivityNotFoundException error) {
		}
	}
}
