package com.mybrowser.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Single GeckoRuntime used by the browser.
 *
 * Extensions are installed from Mozilla Add-ons (AMO), or from any direct .xpi link the
 * user opens in-app, at runtime. No XPI is bundled in app/src/main/assets.
 *
 * Three things are required to make WebExtension installs actually work with GeckoView:
 * 1. A WebExtensionController.PromptDelegate must be registered, otherwise Gecko has no
 *    way to ask the app whether the extension's requested permissions are approved and
 *    the install() call never resolves. Previously no delegate was set at all, so every
 *    install silently stalled -- this is the main reason extensions never worked before.
 * 2. To actually show a toolbar icon and let the user open the extension's popup/act on
 *    a click, each installed WebExtension needs a WebExtension.ActionDelegate registered
 *    so we hear about its browser_action (icon + title) and can react to taps.
 * 3. GeckoRuntimeSettings.Builder.extensionsProcessEnabled(true) must be set (it defaults
 *    to false). Without it, install()/list()/uninstall() and the action/icon plumbing all
 *    keep working -- that bookkeeping runs in the main process -- but Gecko never spawns
 *    the actual extension process, so background scripts, content scripts, and webRequest
 *    listeners silently never run. This is why extensions used to appear installed but do
 *    nothing (uBlock blocking no ads, Dark Reader repainting nothing). Verified against
 *    Mozilla's own GeckoViewActivity.java reference app, which always sets this flag.
 *
 * NOTE for maintainers: this project pins geckoview 130.0.20240829075237. The exact method
 * name for the install-permission callback changed across GeckoView releases (older/`130`
 * builds expose `onInstallPrompt(extension)`, while builds from ~v133 onward renamed it to
 * `onInstallPromptRequest(extension, permissions, origins, dataCollectionPermissions)` and
 * changed its return type). The override below targets the v130 API. If you bump the
 * geckoview version, check WebExtensionController.PromptDelegate in Android Studio's
 * autocomplete and rename/adjust this override to match.
 */
object GeckoEngine {
    private const val TAG = "GeckoEngine"
    private var runtime: GeckoRuntime? = null

    /** Toolbar actions for the currently visible tab. */
    val actionsByExtensionId = mutableStateMapOf<String, WebExtension.Action>()
    val extensionIcons = mutableStateMapOf<String, Bitmap>()

    var popupSession by mutableStateOf<GeckoSession?>(null)
        private set

    private var activeSession: GeckoSession? = null

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(
            extension: WebExtension,
            session: GeckoSession?,
            action: WebExtension.Action
        ) {
            // GeckoView may deliver either a global action (session == null) or a
            // session-scoped action. Only expose the action belonging to the
            // currently visible tab.
            if (session != null && session !== activeSession) return

            if (action.enabled == false) {
                actionsByExtensionId.remove(extension.id)
                extensionIcons.remove(extension.id)
                return
            }

            actionsByExtensionId[extension.id] = action
            action.icon?.getBitmap(96)?.accept(
                { bitmap ->
                    if (bitmap != null) {
                        extensionIcons[extension.id] = bitmap
                    }
                },
                { error ->
                    Log.w(TAG, "Unable to load action icon for ${extension.id}", error)
                }
            )
        }

        override fun onTogglePopup(
            extension: WebExtension,
            action: WebExtension.Action
        ): GeckoResult<GeckoSession>? = openPopup()

        override fun onOpenPopup(
            extension: WebExtension,
            action: WebExtension.Action
        ): GeckoResult<GeckoSession>? = openPopup()

        private fun openPopup(): GeckoResult<GeckoSession>? {
            popupSession?.close()
            val rt = runtime ?: return null

            val session = GeckoSession()
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCloseRequest(session: GeckoSession) {
                    dismissPopup()
                }
            }
            session.open(rt)
            popupSession = session
            return GeckoResult.fromValue(session)
        }
    }

    fun dismissPopup() {
        popupSession?.close()
        popupSession = null
    }

    /**
     * Marks the session which is currently visible in the browser.
     *
     * WebExtensions use this state for tabs.onActivated and for tab-scoped
     * browser/page actions. Without it, many real-world extensions install
     * successfully but do not behave like they do in Firefox.
     */
    fun setActiveSession(session: GeckoSession?, active: Boolean) {
        val controller = runtime?.webExtensionController
        if (session == null) return

        if (active) {
            activeSession = session
            session.setActive(true)
            controller?.setTabActive(session, true)
        } else {
            controller?.setTabActive(session, false)
            session.setActive(false)
            if (activeSession === session) {
                activeSession = null
            }
        }

        // Refresh visible action state after a tab switch.
        if (active) {
            actionsByExtensionId.clear()
            extensionIcons.clear()
            controller?.list()?.accept(
                { extensions -> extensions?.forEach { registerExtension(it) } },
                { error -> Log.w(TAG, "Unable to refresh extension actions", error) }
            )
        }
    }

    private fun registerExtension(extension: WebExtension) {
        extension.setActionDelegate(actionDelegate)
    }

    @Synchronized
    fun getRuntime(context: Context): GeckoRuntime {
        runtime?.let { return it }

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            // WebExtension background/content scripts and webRequest listeners
            // require the dedicated extension process.
            .extensionsProcessEnabled(true)
            .build()

        val rt = GeckoRuntime.create(context.applicationContext, settings)
        val controller = rt.webExtensionController

        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            // GeckoView 130 API.
            override fun onInstallPrompt(
                extension: WebExtension
            ): GeckoResult<AllowOrDeny>? {
                Log.i(TAG, "Allowing extension install: ${extension.id} (${extension.metaData?.name})")
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }

        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                registerExtension(extension)
            }

            override fun onReady(extension: WebExtension) {
                registerExtension(extension)
            }

            override fun onUninstalled(extension: WebExtension) {
                actionsByExtensionId.remove(extension.id)
                extensionIcons.remove(extension.id)
            }

            override fun onInstallationFailed(
                extension: WebExtension?,
                installException: WebExtension.InstallException
            ) {
                Log.e(TAG, "Extension installation failed: ${extension?.id}", installException)
            }
        })

        // If Gecko disables extension process spawning after repeated crashes,
        // explicitly re-enable it for the browser session.
        controller.setExtensionProcessDelegate(object : WebExtensionController.ExtensionProcessDelegate {
            override fun onDisabledProcessSpawning() {
                Log.e(TAG, "Gecko disabled extension process spawning; re-enabling")
                controller.enableExtensionProcessSpawning()
            }
        })

        runtime = rt
        return rt
    }

    fun installExtension(
        context: Context,
        xpiUrl: String,
        onSuccess: (WebExtension) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (!xpiUrl.startsWith("https://", ignoreCase = true) &&
            !xpiUrl.startsWith("http://", ignoreCase = true)
        ) {
            onError(IllegalArgumentException("Extension URL must be HTTP(S): $xpiUrl"))
            return
        }

        val controller = getRuntime(context).webExtensionController
        controller.install(
            xpiUrl,
            WebExtensionController.INSTALLATION_METHOD_MANAGER
        ).accept(
            { extension ->
                if (extension != null) {
                    registerExtension(extension)
                    Log.i(TAG, "Extension installed and registered: ${extension.id}")
                    onSuccess(extension)
                } else {
                    onError(IllegalStateException("GeckoView returned no extension"))
                }
            },
            { error ->
                val cause = error ?: IllegalStateException("Unknown extension install error")
                Log.e(TAG, "Extension install failed: $xpiUrl", cause)
                onError(cause)
            }
        )
    }

    fun listExtensions(context: Context, onResult: (List<WebExtension>) -> Unit) {
        getRuntime(context).webExtensionController.list().accept(
            { extensions ->
                val list = extensions ?: emptyList()
                list.forEach { registerExtension(it) }
                onResult(list)
            },
            { error ->
                Log.e(TAG, "Unable to list extensions", error)
                onResult(emptyList())
            }
        )
    }

    fun uninstallExtension(
        context: Context,
        extension: WebExtension,
        onDone: () -> Unit
    ) {
        getRuntime(context).webExtensionController.uninstall(extension).accept(
            {
                actionsByExtensionId.remove(extension.id)
                extensionIcons.remove(extension.id)
                onDone()
            },
            { error ->
                Log.e(TAG, "Unable to uninstall extension", error)
                onDone()
            }
        )
    }

    fun clickAction(extensionId: String) {
        actionsByExtensionId[extensionId]?.click()
    }
}
