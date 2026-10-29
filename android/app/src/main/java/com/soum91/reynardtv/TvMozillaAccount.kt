package com.soum91.reynardtv

import android.app.Activity
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.DeviceConfig
import mozilla.components.concept.sync.DeviceType
import mozilla.components.concept.sync.FxAEntryPoint
import mozilla.components.service.fxa.FxaAuthData
import mozilla.components.service.fxa.ServerConfig
import mozilla.components.service.fxa.manager.FxaAccountManager
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.net.URI

/**
 * Private test of Mozilla's existing Android OAuth/WebChannel account integration.
 *
 * Mozilla's component handles PKCE, encrypted Sync scoped keys, access-token lifecycle
 * and persistent storage. The app deliberately does not enable Sync stores yet:
 * Reynard TV's local bookmark/history formats are not Firefox Sync stores.
 */
class TvMozillaAccount(
    private val activity: Activity,
    runtime: GeckoRuntime,
    private val onStateChanged: () -> Unit,
) {
    companion object {
        private const val EXTENSION_ID = "fxawebchannel@reynardtv.test"
        private const val NATIVE_APP = "reynardFxaWebChannel"
        private const val CHANNEL = "account_updates"

        // Public client identifier belonging to Firefox for Android (Fenix).
        // ONLY for this non-production, separate-package compatibility experiment.
        // A Reynard-specific OAuth registration is required for distribution.
        private const val EXPERIMENT_CLIENT_ID = "a2270f727f45f648"
        private const val WEBCHANNEL_REDIRECT = "urn:ietf:wg:oauth:2.0:oob:oauth-redirect-webchannel"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager = FxaAccountManager(
        context = activity.applicationContext,
        serverConfig = ServerConfig.release(EXPERIMENT_CLIENT_ID, WEBCHANNEL_REDIRECT),
        deviceConfig = DeviceConfig(
            name = "Reynard TV (test)",
            type = DeviceType.TV,
            capabilities = emptySet(),
            secureStateAtRest = true,
        ),
        syncConfig = null,
    )
    private val controller = runtime.webExtensionController
    private val sessions = mutableSetOf<GeckoSession>()
    private var extension: WebExtension? = null
    private var initialized = false
    private var extensionError: String? = null

    init {
        controller.ensureBuiltIn("resource://android/assets/fxawebchannel/", EXTENSION_ID)
            .accept({ installed ->
                extension = installed
                sessions.forEach { register(it, installed) }
            }, { error ->
                extensionError = error?.message ?: "WebChannel installation failed"
            })
        scope.launch {
            try {
                manager.start()
                initialized = true
                onStateChanged()
            } catch (error: Exception) {
                notifyUser("Mozilla Account initialization failed: ${error.message}")
            }
        }
    }

    val isSignedIn: Boolean
        get() = initialized && manager.authenticatedAccount() != null

    val email: String?
        get() = manager.accountProfile()?.email

    fun attach(session: GeckoSession) {
        sessions.add(session)
        extension?.let { register(session, it) }
    }

    fun detach(session: GeckoSession) {
        sessions.remove(session)
    }

    private fun register(session: GeckoSession, extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val sender = port.sender
                val origin = runCatching { URI(sender.url) }.getOrNull()
                if (port.name != NATIVE_APP ||
                    sender.webExtension.id != EXTENSION_ID ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    !sender.isTopLevel ||
                    sender.session !== session ||
                    origin?.scheme != "https" ||
                    origin.host != "accounts.firefox.com" ||
                    origin.userInfo != null ||
                    (origin.port != -1 && origin.port != 443)
                ) {
                    port.disconnect()
                    return
                }
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val envelope = message as? JSONObject ?: return
                        if (envelope.optString("id") != CHANNEL) return
                        val payload = envelope.optJSONObject("message") ?: return
                        val id = payload.opt("messageId") ?: ""
                        val data = payload.optJSONObject("data") ?: JSONObject()
                        when (val command = payload.optString("command")) {
                            "fxaccounts:fxa_status" -> {
                                reply(port, command, id, JSONObject()
                                    .put("capabilities", JSONObject().put("engines", org.json.JSONArray()))
                                    .put("signedInUser", JSONObject.NULL))
                            }
                            "fxaccounts:can_link_account" -> {
                                val current = email
                                val requested = data.optString("email")
                                val allowed = current.isNullOrEmpty() ||
                                    requested.isEmpty() || current.equals(requested, ignoreCase = true)
                                reply(port, command, id, JSONObject().put("ok", allowed))
                            }
                            "fxaccounts:oauth_login" -> finish(data)
                            // The webpage is not permitted to sign Reynard out or mutate account state.
                        }
                    }
                })
            }
        }, NATIVE_APP)
    }

    private fun reply(port: WebExtension.Port, command: String, id: Any, data: JSONObject) {
        port.postMessage(JSONObject().put("id", CHANNEL).put(
            "message", JSONObject()
                .put("command", command)
                .put("messageId", id)
                .put("data", data)
        ))
    }

    fun beginSignIn(openTab: (String) -> Unit) {
        val problem = when {
            !initialized -> "Mozilla Account is still starting. Try again in a moment."
            extension == null -> extensionError ?: "Account WebChannel is still installing. Try again."
            else -> null
        }
        if (problem != null) {
            notifyUser(problem)
            return
        }
        scope.launch {
            try {
                val url = manager.beginAuthentication(
                    entrypoint = object : FxAEntryPoint {
                        override val entryName = "reynard-tv-fxa-experiment"
                    },
                )
                if (url.isNullOrBlank()) notifyUser("Mozilla Account did not provide a sign-in URL.")
                else openTab(url)
            } catch (error: Exception) {
                notifyUser("Could not start Mozilla sign-in: ${error.message}")
            }
        }
    }

    private fun finish(data: JSONObject) {
        val code = data.optString("code")
        val state = data.optString("state")
        if (code.isBlank() || state.isBlank() || !initialized) return
        val action = when (data.optString("action")) {
            "signup" -> AuthType.Signup
            "pairing" -> AuthType.Pairing
            else -> AuthType.Signin
        }
        // State and PKCE verifier are validated by Mozilla's account manager.
        scope.launch {
            try {
                val accepted = manager.finishAuthentication(FxaAuthData(
                    authType = action,
                    code = code,
                    state = state,
                ))
                if (accepted) {
                    onStateChanged()
                    notifyUser("Mozilla Account connected. Firefox Sync data is not enabled yet.")
                } else {
                    notifyUser("Sign-in was rejected (unexpected or expired OAuth state).")
                }
            } catch (error: Exception) {
                notifyUser("Mozilla Account sign-in failed: ${error.message}")
            }
        }
    }

    fun signOut() {
        if (!initialized) return
        scope.launch {
            try {
                manager.logout()
                onStateChanged()
                notifyUser("Signed out of Mozilla Account")
            } catch (error: Exception) {
                notifyUser("Could not sign out: ${error.message}")
            }
        }
    }

    private fun notifyUser(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    fun close() {
        scope.cancel()
        manager.close()
        sessions.clear()
    }
}
