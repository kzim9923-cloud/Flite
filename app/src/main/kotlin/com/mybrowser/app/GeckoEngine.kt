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
 * Two things are required to make WebExtension installs actually work with GeckoView:
 * 1. A WebExtensionController.PromptDelegate must be registered, otherwise Gecko has no
 *    way to ask the app whether the extension's requested permissions are approved and
 *    the install() call never resolves. Previously no delegate was set at all, so every
 *    install silently stalled -- this is the main reason extensions never worked before.
 * 2. To actually show a toolbar icon and let the user open the extension's popup/act on
 *    a click, each installed WebExtension needs a WebExtension.ActionDelegate registered
 *    so we hear about its browser_action (icon + title) and can react to taps.
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

    /** Default (global) browser_action for each installed+enabled extension, keyed by extension id. */
    val actionsByExtensionId = mutableStateMapOf<String, WebExtension.Action>()

    /** Resolved action-button icon bitmaps, keyed by extension id. Populated asynchronously. */
    val extensionIcons = mutableStateMapOf<String, Bitmap>()

    /** Non-null while an extension popup (e.g. uBlock Origin's stats panel) should be shown. */
    var popupSession by mutableStateOf<GeckoSession?>(null)
        private set

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(
            extension: WebExtension,
            session: GeckoSession?,
            action: WebExtension.Action
        ) {
            // We only surface the default/global action in the bottom bar (no per-tab
            // overrides), which covers how uBlock Origin / Dark Reader / Decentraleyes work.
            if (session != null) return

            if (action.enabled == false) {
                actionsByExtensionId.remove(extension.id)
                extensionIcons.remove(extension.id)
                return
            }

            actionsByExtensionId[extension.id] = action
            val icon = action.icon
            if (icon != null) {
                icon.getBitmap(96).accept(
                    { bitmap -> if (bitmap != null) extensionIcons[extension.id] = bitmap },
                    { error -> Log.w(TAG, "Unable to load action icon for ${extension.id}", error) }
                )
            }
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
            // GeckoView marks this session as a popup and loads the extension's popup
            // page into it automatically once we return the session below.
            return GeckoResult.fromValue(session)
        }
    }

    fun dismissPopup() {
        popupSession?.close()
        popupSession = null
    }

    /** Hooks up the action (toolbar icon) delegate for an extension we just installed or listed. */
    private fun registerExtension(extension: WebExtension) {
        extension.setActionDelegate(actionDelegate)
    }

    @Synchronized
    fun getRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val rt = GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .build()
            )
            rt.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
                // Called for every new install. These are add-ons the user explicitly chose
                // to install (from our curated list or by tapping "Add to Firefox" on AMO),
                // so we approve the requested permissions automatically instead of hanging
                // forever with no delegate response, which is what happened before.
                override fun onInstallPrompt(extension: WebExtension): GeckoResult<AllowOrDeny>? {
                    Log.i(TAG, "Approving install of ${extension.id} (${extension.metaData?.name})")
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
            }
            runtime = rt
        }
        return runtime!!
    }

    fun installExtension(
        context: Context,
        xpiUrl: String,
        onSuccess: (WebExtension) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val controller = getRuntime(context).webExtensionController
        controller.install(
            xpiUrl,
            WebExtensionController.INSTALLATION_METHOD_MANAGER
        ).accept(
            { extension ->
                if (extension != null) {
                    Log.i(TAG, "Extension installed: ${extension.id}")
                    registerExtension(extension)
                    onSuccess(extension)
                } else {
                    onError(IllegalStateException("GeckoView returned no extension"))
                }
            },
            { error ->
                Log.e(TAG, "Extension install failed: $xpiUrl", error)
                onError(error ?: IllegalStateException("Unknown extension install error"))
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

    /** Lets the user activate an extension from its toolbar icon. */
    fun clickAction(extensionId: String) {
        actionsByExtensionId[extensionId]?.click()
    }
}
