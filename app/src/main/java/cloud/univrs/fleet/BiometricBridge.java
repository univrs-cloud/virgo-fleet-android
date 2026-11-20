package cloud.univrs.fleet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

final class BiometricBridge {
	private static final String KEYSTORE = "AndroidKeyStore";
	private static final String ALIAS_PREFIX = "fleet-biometric-";
	private static final String PREFERENCES = "biometric";
	private static final String CREDENTIAL_ID = "credentialId";
	private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

	private final AppCompatActivity activity;
	private final SharedPreferences preferences;
	private boolean isPrompting = false;

	private interface PromptResult {
		void onSuccess(Signature signature) throws GeneralSecurityException;

		void onCancel();

		void onError(String message);
	}

	BiometricBridge(AppCompatActivity activity) {
		this.activity = activity;
		this.preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
	}

	void handle(String data, JavaScriptReplyProxy replyProxy) {
		if (data == null) {
			return;
		}
		JSONObject request;
		try {
			request = new JSONObject(data);
		} catch (JSONException error) {
			return;
		}
		String id = request.optString("id");
		String action = request.optString("action");
		String challenge = request.optString("challenge");
		switch (action) {
			case "status":
				status(id, replyProxy);
				break;
			case "enroll":
				enroll(id, challenge, replyProxy);
				break;
			case "sign":
				sign(id, challenge, replyProxy);
				break;
			case "forget":
				forget();
				reply(replyProxy, id, succeeded());
				break;
			default:
				reply(replyProxy, id, failed("This action is not supported."));
				break;
		}
	}

	private void status(String id, JavaScriptReplyProxy replyProxy) {
		boolean isAvailable = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
		String credentialId = preferences.getString(CREDENTIAL_ID, null);
		boolean isEnrolled = false;
		if (credentialId != null) {
			try {
				isEnrolled = keyStore().containsAlias(alias(credentialId));
			} catch (GeneralSecurityException | IOException error) {
				isEnrolled = false;
			}
		}
		JSONObject body = new JSONObject();
		put(body, "available", isAvailable);
		put(body, "enrolled", isEnrolled);
		reply(replyProxy, id, body);
	}

	private void enroll(String id, String challenge, JavaScriptReplyProxy replyProxy) {
		if (isPrompting) {
			reply(replyProxy, id, failed("Biometric sign-in is already open."));
			return;
		}
		if (challenge.isEmpty()) {
			reply(replyProxy, id, failed("Could not set up biometric sign-in. Try again."));
			return;
		}
		String credentialId = UUID.randomUUID().toString();
		String alias = alias(credentialId);
		KeyPair keyPair;
		Signature signature;
		JSONArray certificates = new JSONArray();
		try {
			keyPair = createKey(alias, challenge);
			signature = Signature.getInstance(SIGNATURE_ALGORITHM);
			signature.initSign(keyPair.getPrivate());
			Certificate[] chain = keyStore().getCertificateChain(alias);
			if (chain == null || chain.length < 2) {
				throw new GeneralSecurityException("This phone cannot attest its keys.");
			}
			for (Certificate certificate : chain) {
				certificates.put(encode(certificate.getEncoded()));
			}
		} catch (GeneralSecurityException | IOException error) {
			deleteKey(alias);
			reply(replyProxy, id, failed("Could not set up biometric sign-in on this phone."));
			return;
		}
		String publicKey = encode(keyPair.getPublic().getEncoded());
		prompt(signature, "Turn on biometric sign-in", new PromptResult() {
			@Override
			public void onSuccess(Signature unlocked) throws GeneralSecurityException {
				unlocked.update(challenge.getBytes(StandardCharsets.UTF_8));
				String signed = encode(unlocked.sign());
				String previous = preferences.getString(CREDENTIAL_ID, null);
				if (previous != null) {
					deleteKey(alias(previous));
				}
				preferences.edit().putString(CREDENTIAL_ID, credentialId).apply();
				JSONObject body = succeeded();
				put(body, "credentialId", credentialId);
				put(body, "publicKey", publicKey);
				put(body, "signature", signed);
				put(body, "certificates", certificates);
				reply(replyProxy, id, body);
			}

			@Override
			public void onCancel() {
				deleteKey(alias);
				reply(replyProxy, id, cancelled());
			}

			@Override
			public void onError(String message) {
				deleteKey(alias);
				reply(replyProxy, id, failed(message));
			}
		});
	}

	private void sign(String id, String challenge, JavaScriptReplyProxy replyProxy) {
		if (isPrompting) {
			reply(replyProxy, id, failed("Biometric sign-in is already open."));
			return;
		}
		String credentialId = preferences.getString(CREDENTIAL_ID, null);
		if (credentialId == null || challenge.isEmpty()) {
			reply(replyProxy, id, failed("Biometric sign-in is not set up on this phone."));
			return;
		}
		Signature signature;
		try {
			Key key = keyStore().getKey(alias(credentialId), null);
			if (!(key instanceof PrivateKey)) {
				forget();
				reply(replyProxy, id, failed("Biometric sign-in is not set up on this phone."));
				return;
			}
			signature = Signature.getInstance(SIGNATURE_ALGORITHM);
			signature.initSign((PrivateKey) key);
		} catch (KeyPermanentlyInvalidatedException error) {
			forget();
			reply(replyProxy, id, failed("The fingerprints or face on this phone changed. Sign in with your password, then turn on biometric sign-in again."));
			return;
		} catch (GeneralSecurityException | IOException error) {
			reply(replyProxy, id, failed("Biometric sign-in didn't work. Sign in with your password."));
			return;
		}
		prompt(signature, "Sign in to univrs Fleet", new PromptResult() {
			@Override
			public void onSuccess(Signature unlocked) throws GeneralSecurityException {
				unlocked.update(challenge.getBytes(StandardCharsets.UTF_8));
				JSONObject body = succeeded();
				put(body, "credentialId", credentialId);
				put(body, "signature", encode(unlocked.sign()));
				reply(replyProxy, id, body);
			}

			@Override
			public void onCancel() {
				reply(replyProxy, id, cancelled());
			}

			@Override
			public void onError(String message) {
				reply(replyProxy, id, failed(message));
			}
		});
	}

	private void forget() {
		String credentialId = preferences.getString(CREDENTIAL_ID, null);
		if (credentialId != null) {
			deleteKey(alias(credentialId));
		}
		preferences.edit().remove(CREDENTIAL_ID).apply();
	}

	private void prompt(Signature signature, String title, PromptResult result) {
		isPrompting = true;
		BiometricPrompt biometricPrompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
			@Override
			public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult authenticationResult) {
				isPrompting = false;
				BiometricPrompt.CryptoObject cryptoObject = authenticationResult.getCryptoObject();
				Signature unlocked = cryptoObject != null ? cryptoObject.getSignature() : null;
				if (unlocked == null) {
					result.onError("Biometric sign-in didn't work. Try again.");
					return;
				}
				try {
					result.onSuccess(unlocked);
				} catch (GeneralSecurityException error) {
					result.onError("Biometric sign-in didn't work. Try again.");
				}
			}

			@Override
			public void onAuthenticationError(int errorCode, CharSequence errorMessage) {
				isPrompting = false;
				if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED) {
					result.onCancel();
					return;
				}
				result.onError(errorMessage.toString());
			}
		});
		BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
			.setTitle(title)
			.setNegativeButtonText("Use password")
			.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
			.build();
		biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(signature));
	}

	private static KeyPair createKey(String alias, String challenge) throws GeneralSecurityException {
		KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
			.setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
			.setDigests(KeyProperties.DIGEST_SHA256)
			.setUserAuthenticationRequired(true)
			.setInvalidatedByBiometricEnrollment(true)
			.setAttestationChallenge(challenge.getBytes(StandardCharsets.UTF_8));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
		}
		KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
		generator.initialize(builder.build());
		return generator.generateKeyPair();
	}

	private static KeyStore keyStore() throws GeneralSecurityException, IOException {
		KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
		keyStore.load(null);
		return keyStore;
	}

	private static void deleteKey(String alias) {
		try {
			keyStore().deleteEntry(alias);
		} catch (GeneralSecurityException | IOException error) {
		}
	}

	private static String alias(String credentialId) {
		return ALIAS_PREFIX + credentialId;
	}

	private static String encode(byte[] bytes) {
		return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
	}

	private static JSONObject succeeded() {
		JSONObject body = new JSONObject();
		put(body, "status", "succeeded");
		return body;
	}

	private static JSONObject cancelled() {
		JSONObject body = new JSONObject();
		put(body, "status", "cancelled");
		return body;
	}

	private static JSONObject failed(String message) {
		JSONObject body = new JSONObject();
		put(body, "status", "failed");
		put(body, "message", message);
		return body;
	}

	private static void put(JSONObject body, String name, Object value) {
		try {
			body.put(name, value);
		} catch (JSONException error) {
		}
	}

	private static void reply(JavaScriptReplyProxy replyProxy, String id, JSONObject body) {
		put(body, "id", id);
		replyProxy.postMessage(body.toString());
	}
}
