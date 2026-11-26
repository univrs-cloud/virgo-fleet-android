package cloud.univrs.fleet;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
	private static final String HOST = "fleet.univrs.cloud";
	private static final String ORIGIN = "https://" + HOST;
	private static final String BRIDGE_NAME = "univrsFleet";
	private static final String SCROLL_BRIDGE_NAME = "univrsFleetScroll";
	private static final String SCROLL_MESSAGE_PULL = "pull";
	private static final String SCROLL_SCRIPT = "(() => {"
		+ "if (window !== window.top) return;"
		+ "let path = [];"
		+ "let canPull = false;"
		+ "const isScrolled = () => path.some((node) => node instanceof Element && node.scrollTop > 0);"
		+ "addEventListener('touchstart', (event) => {"
		+ "path = event.composedPath();"
		+ "canPull = event.touches.length === 1 && !isScrolled();"
		+ "univrsFleetScroll.postMessage(canPull ? 'pull' : 'scroll');"
		+ "}, { capture: true, passive: true });"
		+ "addEventListener('touchmove', () => {"
		+ "if (canPull && isScrolled()) {"
		+ "canPull = false;"
		+ "univrsFleetScroll.postMessage('scroll');"
		+ "}"
		+ "}, { capture: true, passive: true });"
		+ "})();";
	private static final String BARS_BRIDGE_NAME = "univrsFleetBars";
	private static final String BARS_SCRIPT = "(() => {"
		+ "if (window !== window.top) return;"
		+ "const canvas = document.createElement('canvas');"
		+ "canvas.width = 1;"
		+ "canvas.height = 1;"
		+ "const context = canvas.getContext('2d', { willReadFrequently: true });"
		+ "let last = '';"
		+ "let isQueued = false;"
		+ "let retries = 0;"
		+ "let lastElement = null;"
		+ "const parentOf = (node) => node.parentElement || node.getRootNode().host || null;"
		+ "const chainOf = (element) => {"
		+ "const chain = [];"
		+ "for (let node = element; node; node = parentOf(node)) chain.unshift(node);"
		+ "return chain;"
		+ "};"
		+ "const colorOf = (elements) => {"
		+ "context.fillStyle = '#fff';"
		+ "context.fillRect(0, 0, 1, 1);"
		+ "for (const element of elements) {"
		+ "context.fillStyle = 'transparent';"
		+ "context.fillStyle = getComputedStyle(element).backgroundColor;"
		+ "context.fillRect(0, 0, 1, 1);"
		+ "}"
		+ "const [r, g, b] = context.getImageData(0, 0, 1, 1).data;"
		+ "return [r, g, b];"
		+ "};"
		+ "const elementAt = (x, y) => {"
		+ "let element = document.elementFromPoint(x, y);"
		+ "while (element?.shadowRoot) {"
		+ "const inner = element.shadowRoot.elementFromPoint(x, y);"
		+ "if (!inner || inner === element) break;"
		+ "element = inner;"
		+ "}"
		+ "return element || document.body;"
		+ "};"
		+ "const sample = () => {"
		+ "isQueued = false;"
		+ "if (!document.body) return;"
		+ "let element = elementAt(innerWidth / 2, innerHeight - 1);"
		+ "if (element === document.documentElement && lastElement?.isConnected) {"
		+ "element = lastElement;"
		+ "}"
		+ "if (element === document.documentElement && retries < 60) {"
		+ "retries += 1;"
		+ "queue();"
		+ "return;"
		+ "}"
		+ "retries = 0;"
		+ "lastElement = element;"
		+ "const top = colorOf(chainOf(document.body));"
		+ "const bottom = colorOf(chainOf(element));"
		+ "const key = top.join() + '|' + bottom.join();"
		+ "if (key === last) return;"
		+ "last = key;"
		+ "univrsFleetBars.postMessage(JSON.stringify({ top, bottom }));"
		+ "};"
		+ "const queue = () => {"
		+ "if (isQueued) return;"
		+ "isQueued = true;"
		+ "requestAnimationFrame(sample);"
		+ "};"
		+ "new MutationObserver(queue).observe(document.documentElement, { attributes: true, childList: true, subtree: true });"
		+ "addEventListener('DOMContentLoaded', queue);"
		+ "addEventListener('load', queue);"
		+ "addEventListener('resize', queue);"
		+ "addEventListener('scroll', queue, { capture: true, passive: true });"
		+ "addEventListener('transitionend', queue, { capture: true });"
		+ "matchMedia('(prefers-color-scheme: dark)').addEventListener('change', queue);"
		+ "})();";

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
		View bottomBar = new View(this);
		PullRefreshLayout swipeRefresh = new PullRefreshLayout(this);
		webView = new WebView(this);
		swipeRefresh.addView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		swipeRefresh.setOnRefreshListener(() -> webView.reload());
		container.addView(swipeRefresh, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		container.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
		setContentView(container);
		ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
			Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
			FrameLayout.LayoutParams content = (FrameLayout.LayoutParams) swipeRefresh.getLayoutParams();
			content.setMargins(bars.left, bars.top, bars.right, bars.bottom);
			swipeRefresh.setLayoutParams(content);
			FrameLayout.LayoutParams bottom = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
			bottom.height = bars.bottom;
			bottomBar.setLayoutParams(bottom);
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

		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
			WebViewCompat.addWebMessageListener(webView, SCROLL_BRIDGE_NAME, Collections.singleton(ORIGIN), (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
				if (isMainFrame) {
					swipeRefresh.setCanPull(SCROLL_MESSAGE_PULL.equals(message.getData()));
				}
			});
			WebViewCompat.addDocumentStartJavaScript(webView, SCROLL_SCRIPT, Collections.singleton(ORIGIN));
			WebViewCompat.addWebMessageListener(webView, BARS_BRIDGE_NAME, Collections.singleton(ORIGIN), (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
				if (isMainFrame) {
					applyBars(message.getData(), container, bottomBar, insetsController);
				}
			});
			WebViewCompat.addDocumentStartJavaScript(webView, BARS_SCRIPT, Collections.singleton(ORIGIN));
		} else {
			swipeRefresh.setEnabled(false);
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

	private static void applyBars(String data, View top, View bottom, WindowInsetsControllerCompat insetsController) {
		if (data == null) {
			return;
		}
		try {
			JSONObject colors = new JSONObject(data);
			int topColor = colorOf(colors.getJSONArray("top"));
			int bottomColor = colorOf(colors.getJSONArray("bottom"));
			top.setBackgroundColor(topColor);
			bottom.setBackgroundColor(bottomColor);
			insetsController.setAppearanceLightStatusBars(isLight(topColor));
			insetsController.setAppearanceLightNavigationBars(isLight(bottomColor));
		} catch (JSONException error) {
		}
	}

	private static int colorOf(JSONArray rgb) throws JSONException {
		return Color.rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2));
	}

	private static boolean isLight(int color) {
		return ColorUtils.calculateLuminance(color) > 0.5;
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

	private static final class PullRefreshLayout extends SwipeRefreshLayout {
		private boolean canPull;

		PullRefreshLayout(Context context) {
			super(context);
		}

		void setCanPull(boolean canPull) {
			this.canPull = canPull;
		}

		@Override
		public boolean onInterceptTouchEvent(MotionEvent event) {
			int action = event.getActionMasked();
			if (action == MotionEvent.ACTION_DOWN) {
				canPull = false;
			}
			if (action == MotionEvent.ACTION_MOVE && !canPull) {
				return false;
			}
			return super.onInterceptTouchEvent(event);
		}
	}
}
